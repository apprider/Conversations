package eu.siacs.conversations.crypto.axolotl;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.whispersystems.libaxolotl.AxolotlAddress;
import org.whispersystems.libaxolotl.IdentityKey;
import org.whispersystems.libaxolotl.IdentityKeyPair;
import org.whispersystems.libaxolotl.InvalidKeyException;
import org.whispersystems.libaxolotl.InvalidKeyIdException;
import org.whispersystems.libaxolotl.SessionBuilder;
import org.whispersystems.libaxolotl.UntrustedIdentityException;
import org.whispersystems.libaxolotl.ecc.ECPublicKey;
import org.whispersystems.libaxolotl.state.PreKeyBundle;
import org.whispersystems.libaxolotl.state.PreKeyRecord;
import org.whispersystems.libaxolotl.state.SignedPreKeyRecord;
import org.whispersystems.libaxolotl.util.KeyHelper;

import java.security.Security;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.parser.IqParser;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.SerialSingleThreadExecutor;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.OnIqPacketReceived;
import eu.siacs.conversations.xmpp.jid.InvalidJidException;
import eu.siacs.conversations.xmpp.jid.Jid;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;

public class AxolotlService {

	public static final String PEP_PREFIX = "eu.siacs.conversations.axolotl";
	public static final String PEP_DEVICE_LIST = PEP_PREFIX + ".devicelist";
	public static final String PEP_BUNDLES = PEP_PREFIX + ".bundles";

	public static final String LOGPREFIX = "AxolotlService";

	public static final int NUM_KEYS_TO_PUBLISH = 10;

	private final Account account;
	private final XmppConnectionService mXmppConnectionService;
	private final SQLiteAxolotlStore axolotlStore;
	private final SessionMap sessions;
	private final Map<Jid, Set<Integer>> deviceIds;
	private final Map<String, XmppAxolotlMessage> messageCache;
	private final FetchStatusMap fetchStatusMap;
	private final SerialSingleThreadExecutor executor;

	private static class AxolotlAddressMap<T> {
		protected Map<String, Map<Integer, T>> map;
		protected final Object MAP_LOCK = new Object();

		public AxolotlAddressMap() {
			this.map = new HashMap<>();
		}

		public void put(AxolotlAddress address, T value) {
			synchronized (MAP_LOCK) {
				Map<Integer, T> devices = map.get(address.getName());
				if (devices == null) {
					devices = new HashMap<>();
					map.put(address.getName(), devices);
				}
				devices.put(address.getDeviceId(), value);
			}
		}

		public T get(AxolotlAddress address) {
			synchronized (MAP_LOCK) {
				Map<Integer, T> devices = map.get(address.getName());
				if (devices == null) {
					return null;
				}
				return devices.get(address.getDeviceId());
			}
		}

		public Map<Integer, T> getAll(AxolotlAddress address) {
			synchronized (MAP_LOCK) {
				Map<Integer, T> devices = map.get(address.getName());
				if (devices == null) {
					return new HashMap<>();
				}
				return devices;
			}
		}

		public boolean hasAny(AxolotlAddress address) {
			synchronized (MAP_LOCK) {
				Map<Integer, T> devices = map.get(address.getName());
				return devices != null && !devices.isEmpty();
			}
		}

		public void clear() {
			map.clear();
		}

	}

	private static class SessionMap extends AxolotlAddressMap<XmppAxolotlSession> {
		private final XmppConnectionService xmppConnectionService;
		private final Account account;

		public SessionMap(XmppConnectionService service, SQLiteAxolotlStore store, Account account) {
			super();
			this.xmppConnectionService = service;
			this.account = account;
			this.fillMap(store);
		}

		private void putDevicesForJid(String bareJid, List<Integer> deviceIds, SQLiteAxolotlStore store) {
			for (Integer deviceId : deviceIds) {
				AxolotlAddress axolotlAddress = new AxolotlAddress(bareJid, deviceId);
				Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Building session for remote address: " + axolotlAddress.toString());
				String fingerprint = store.loadSession(axolotlAddress).getSessionState().getRemoteIdentityKey().getFingerprint().replaceAll("\\s", "");
				this.put(axolotlAddress, new XmppAxolotlSession(account, store, axolotlAddress, fingerprint));
			}
		}

		private void fillMap(SQLiteAxolotlStore store) {
			List<Integer> deviceIds = store.getSubDeviceSessions(account.getJid().toBareJid().toString());
			putDevicesForJid(account.getJid().toBareJid().toString(), deviceIds, store);
			for (Contact contact : account.getRoster().getContacts()) {
				Jid bareJid = contact.getJid().toBareJid();
				if (bareJid == null) {
					continue; // FIXME: handle this?
				}
				String address = bareJid.toString();
				deviceIds = store.getSubDeviceSessions(address);
				putDevicesForJid(address, deviceIds, store);
			}

		}

		@Override
		public void put(AxolotlAddress address, XmppAxolotlSession value) {
			super.put(address, value);
			xmppConnectionService.syncRosterToDisk(account);
		}
	}

	private static enum FetchStatus {
		PENDING,
		SUCCESS,
		ERROR
	}

	private static class FetchStatusMap extends AxolotlAddressMap<FetchStatus> {

	}

	public static String getLogprefix(Account account) {
		return LOGPREFIX + " (" + account.getJid().toBareJid().toString() + "): ";
	}

	public AxolotlService(Account account, XmppConnectionService connectionService) {
		if (Security.getProvider("BC") == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
		this.mXmppConnectionService = connectionService;
		this.account = account;
		this.axolotlStore = new SQLiteAxolotlStore(this.account, this.mXmppConnectionService);
		this.deviceIds = new HashMap<>();
		this.messageCache = new HashMap<>();
		this.sessions = new SessionMap(mXmppConnectionService, axolotlStore, account);
		this.fetchStatusMap = new FetchStatusMap();
		this.executor = new SerialSingleThreadExecutor();
	}

	public IdentityKey getOwnPublicKey() {
		return axolotlStore.getIdentityKeyPair().getPublicKey();
	}

	public Set<IdentityKey> getKeysWithTrust(SQLiteAxolotlStore.Trust trust) {
		return axolotlStore.getContactKeysWithTrust(account.getJid().toBareJid().toString(), trust);
	}

	public Set<IdentityKey> getKeysWithTrust(SQLiteAxolotlStore.Trust trust, Contact contact) {
		return axolotlStore.getContactKeysWithTrust(contact.getJid().toBareJid().toString(), trust);
	}

	public long getNumTrustedKeys(Contact contact) {
		return axolotlStore.getContactNumTrustedKeys(contact.getJid().toBareJid().toString());
	}

	private AxolotlAddress getAddressForJid(Jid jid) {
		return new AxolotlAddress(jid.toString(), 0);
	}

	private Set<XmppAxolotlSession> findOwnSessions() {
		AxolotlAddress ownAddress = getAddressForJid(account.getJid().toBareJid());
		Set<XmppAxolotlSession> ownDeviceSessions = new HashSet<>(this.sessions.getAll(ownAddress).values());
		return ownDeviceSessions;
	}

	private Set<XmppAxolotlSession> findSessionsforContact(Contact contact) {
		AxolotlAddress contactAddress = getAddressForJid(contact.getJid());
		Set<XmppAxolotlSession> sessions = new HashSet<>(this.sessions.getAll(contactAddress).values());
		return sessions;
	}

	private boolean hasAny(Contact contact) {
		AxolotlAddress contactAddress = getAddressForJid(contact.getJid());
		return sessions.hasAny(contactAddress);
	}

	public void regenerateKeys() {
		axolotlStore.regenerate();
		sessions.clear();
		fetchStatusMap.clear();
		publishBundlesIfNeeded();
		publishOwnDeviceIdIfNeeded();
	}

	public int getOwnDeviceId() {
		return axolotlStore.getLocalRegistrationId();
	}

	public Set<Integer> getOwnDeviceIds() {
		return this.deviceIds.get(account.getJid().toBareJid());
	}

	private void setTrustOnSessions(final Jid jid, @NonNull final Set<Integer> deviceIds,
	                                final SQLiteAxolotlStore.Trust from,
	                                final SQLiteAxolotlStore.Trust to) {
		for (Integer deviceId : deviceIds) {
			AxolotlAddress address = new AxolotlAddress(jid.toBareJid().toString(), deviceId);
			XmppAxolotlSession session = sessions.get(address);
			if (session != null && session.getFingerprint() != null
					&& session.getTrust() == from) {
				session.setTrust(to);
			}
		}
	}

	public void registerDevices(final Jid jid, @NonNull final Set<Integer> deviceIds) {
		if (jid.toBareJid().equals(account.getJid().toBareJid())) {
			if (deviceIds.contains(getOwnDeviceId())) {
				deviceIds.remove(getOwnDeviceId());
			}
			for (Integer deviceId : deviceIds) {
				AxolotlAddress ownDeviceAddress = new AxolotlAddress(jid.toBareJid().toString(), deviceId);
				if (sessions.get(ownDeviceAddress) == null) {
					buildSessionFromPEP(null, ownDeviceAddress, false);
				}
			}
		}
		Set<Integer> expiredDevices = new HashSet<>(axolotlStore.getSubDeviceSessions(jid.toBareJid().toString()));
		expiredDevices.removeAll(deviceIds);
		setTrustOnSessions(jid, expiredDevices, SQLiteAxolotlStore.Trust.TRUSTED,
				SQLiteAxolotlStore.Trust.INACTIVE);
		Set<Integer> newDevices = new HashSet<>(deviceIds);
		setTrustOnSessions(jid, newDevices, SQLiteAxolotlStore.Trust.INACTIVE,
				SQLiteAxolotlStore.Trust.TRUSTED);
		this.deviceIds.put(jid, deviceIds);
		mXmppConnectionService.keyStatusUpdated();
		publishOwnDeviceIdIfNeeded();
	}

	public void wipeOtherPepDevices() {
		Set<Integer> deviceIds = new HashSet<>();
		deviceIds.add(getOwnDeviceId());
		IqPacket publish = mXmppConnectionService.getIqGenerator().publishDeviceIds(deviceIds);
		Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Wiping all other devices from Pep:" + publish);
		mXmppConnectionService.sendIqPacket(account, publish, new OnIqPacketReceived() {
			@Override
			public void onIqPacketReceived(Account account, IqPacket packet) {
				// TODO: implement this!
			}
		});
	}

	public void purgeKey(IdentityKey identityKey) {
		axolotlStore.setFingerprintTrust(identityKey.getFingerprint().replaceAll("\\s", ""), SQLiteAxolotlStore.Trust.COMPROMISED);
	}

	public void publishOwnDeviceIdIfNeeded() {
		IqPacket packet = mXmppConnectionService.getIqGenerator().retrieveDeviceIds(account.getJid().toBareJid());
		mXmppConnectionService.sendIqPacket(account, packet, new OnIqPacketReceived() {
			@Override
			public void onIqPacketReceived(Account account, IqPacket packet) {
				Element item = mXmppConnectionService.getIqParser().getItem(packet);
				Set<Integer> deviceIds = mXmppConnectionService.getIqParser().deviceIds(item);
				if (deviceIds == null) {
					deviceIds = new HashSet<Integer>();
				}
				if (!deviceIds.contains(getOwnDeviceId())) {
					deviceIds.add(getOwnDeviceId());
					IqPacket publish = mXmppConnectionService.getIqGenerator().publishDeviceIds(deviceIds);
					Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Own device " + getOwnDeviceId() + " not in PEP devicelist. Publishing: " + publish);
					mXmppConnectionService.sendIqPacket(account, publish, new OnIqPacketReceived() {
						@Override
						public void onIqPacketReceived(Account account, IqPacket packet) {
							// TODO: implement this!
						}
					});
				}
			}
		});
	}

	public void publishBundlesIfNeeded() {
		IqPacket packet = mXmppConnectionService.getIqGenerator().retrieveBundlesForDevice(account.getJid().toBareJid(), getOwnDeviceId());
		mXmppConnectionService.sendIqPacket(account, packet, new OnIqPacketReceived() {
			@Override
			public void onIqPacketReceived(Account account, IqPacket packet) {
				PreKeyBundle bundle = mXmppConnectionService.getIqParser().bundle(packet);
				Map<Integer, ECPublicKey> keys = mXmppConnectionService.getIqParser().preKeyPublics(packet);
				boolean flush = false;
				if (bundle == null) {
					Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Received invalid bundle:" + packet);
					bundle = new PreKeyBundle(-1, -1, -1, null, -1, null, null, null);
					flush = true;
				}
				if (keys == null) {
					Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Received invalid prekeys:" + packet);
				}
				try {
					boolean changed = false;
					// Validate IdentityKey
					IdentityKeyPair identityKeyPair = axolotlStore.getIdentityKeyPair();
					if (flush || !identityKeyPair.getPublicKey().equals(bundle.getIdentityKey())) {
						Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding own IdentityKey " + identityKeyPair.getPublicKey() + " to PEP.");
						changed = true;
					}

					// Validate signedPreKeyRecord + ID
					SignedPreKeyRecord signedPreKeyRecord;
					int numSignedPreKeys = axolotlStore.loadSignedPreKeys().size();
					try {
						signedPreKeyRecord = axolotlStore.loadSignedPreKey(bundle.getSignedPreKeyId());
						if (flush
								|| !bundle.getSignedPreKey().equals(signedPreKeyRecord.getKeyPair().getPublicKey())
								|| !Arrays.equals(bundle.getSignedPreKeySignature(), signedPreKeyRecord.getSignature())) {
							Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding new signedPreKey with ID " + (numSignedPreKeys + 1) + " to PEP.");
							signedPreKeyRecord = KeyHelper.generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1);
							axolotlStore.storeSignedPreKey(signedPreKeyRecord.getId(), signedPreKeyRecord);
							changed = true;
						}
					} catch (InvalidKeyIdException e) {
						Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding new signedPreKey with ID " + (numSignedPreKeys + 1) + " to PEP.");
						signedPreKeyRecord = KeyHelper.generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1);
						axolotlStore.storeSignedPreKey(signedPreKeyRecord.getId(), signedPreKeyRecord);
						changed = true;
					}

					// Validate PreKeys
					Set<PreKeyRecord> preKeyRecords = new HashSet<>();
					if (keys != null) {
						for (Integer id : keys.keySet()) {
							try {
								PreKeyRecord preKeyRecord = axolotlStore.loadPreKey(id);
								if (preKeyRecord.getKeyPair().getPublicKey().equals(keys.get(id))) {
									preKeyRecords.add(preKeyRecord);
								}
							} catch (InvalidKeyIdException ignored) {
							}
						}
					}
					int newKeys = NUM_KEYS_TO_PUBLISH - preKeyRecords.size();
					if (newKeys > 0) {
						List<PreKeyRecord> newRecords = KeyHelper.generatePreKeys(
								axolotlStore.getCurrentPreKeyId() + 1, newKeys);
						preKeyRecords.addAll(newRecords);
						for (PreKeyRecord record : newRecords) {
							axolotlStore.storePreKey(record.getId(), record);
						}
						changed = true;
						Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding " + newKeys + " new preKeys to PEP.");
					}


					if (changed) {
						IqPacket publish = mXmppConnectionService.getIqGenerator().publishBundles(
								signedPreKeyRecord, axolotlStore.getIdentityKeyPair().getPublicKey(),
								preKeyRecords, getOwnDeviceId());
						Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + ": Bundle " + getOwnDeviceId() + " in PEP not current. Publishing: " + publish);
						mXmppConnectionService.sendIqPacket(account, publish, new OnIqPacketReceived() {
							@Override
							public void onIqPacketReceived(Account account, IqPacket packet) {
								// TODO: implement this!
								Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Published bundle, got: " + packet);
							}
						});
					}
				} catch (InvalidKeyException e) {
					Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Failed to publish bundle " + getOwnDeviceId() + ", reason: " + e.getMessage());
					return;
				}
			}
		});
	}

	public boolean isContactAxolotlCapable(Contact contact) {

		Jid jid = contact.getJid().toBareJid();
		AxolotlAddress address = new AxolotlAddress(jid.toString(), 0);
		return sessions.hasAny(address) ||
				(deviceIds.containsKey(jid) && !deviceIds.get(jid).isEmpty());
	}

	public SQLiteAxolotlStore.Trust getFingerprintTrust(String fingerprint) {
		return axolotlStore.getFingerprintTrust(fingerprint);
	}

	public void setFingerprintTrust(String fingerprint, SQLiteAxolotlStore.Trust trust) {
		axolotlStore.setFingerprintTrust(fingerprint, trust);
	}

	private void buildSessionFromPEP(final Conversation conversation, final AxolotlAddress address, final boolean flushWaitingQueueAfterFetch) {
		Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Building new sesstion for " + address.getDeviceId());

		try {
			IqPacket bundlesPacket = mXmppConnectionService.getIqGenerator().retrieveBundlesForDevice(
					Jid.fromString(address.getName()), address.getDeviceId());
			Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Retrieving bundle: " + bundlesPacket);
			mXmppConnectionService.sendIqPacket(account, bundlesPacket, new OnIqPacketReceived() {
				private void finish() {
					AxolotlAddress ownAddress = new AxolotlAddress(account.getJid().toBareJid().toString(), 0);
					if (!fetchStatusMap.getAll(ownAddress).containsValue(FetchStatus.PENDING)
							&& !fetchStatusMap.getAll(address).containsValue(FetchStatus.PENDING)) {
						if (flushWaitingQueueAfterFetch && conversation != null) {
							conversation.findUnsentMessagesWithEncryption(Message.ENCRYPTION_AXOLOTL,
									new Conversation.OnMessageFound() {
										@Override
										public void onMessageFound(Message message) {
											processSending(message, false);
										}
									});
						}
						mXmppConnectionService.keyStatusUpdated();
					}
				}

				@Override
				public void onIqPacketReceived(Account account, IqPacket packet) {
					Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Received preKey IQ packet, processing...");
					final IqParser parser = mXmppConnectionService.getIqParser();
					final List<PreKeyBundle> preKeyBundleList = parser.preKeys(packet);
					final PreKeyBundle bundle = parser.bundle(packet);
					if (preKeyBundleList.isEmpty() || bundle == null) {
						Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "preKey IQ packet invalid: " + packet);
						fetchStatusMap.put(address, FetchStatus.ERROR);
						finish();
						return;
					}
					Random random = new Random();
					final PreKeyBundle preKey = preKeyBundleList.get(random.nextInt(preKeyBundleList.size()));
					if (preKey == null) {
						//should never happen
						fetchStatusMap.put(address, FetchStatus.ERROR);
						finish();
						return;
					}

					final PreKeyBundle preKeyBundle = new PreKeyBundle(0, address.getDeviceId(),
							preKey.getPreKeyId(), preKey.getPreKey(),
							bundle.getSignedPreKeyId(), bundle.getSignedPreKey(),
							bundle.getSignedPreKeySignature(), bundle.getIdentityKey());

					axolotlStore.saveIdentity(address.getName(), bundle.getIdentityKey());

					try {
						SessionBuilder builder = new SessionBuilder(axolotlStore, address);
						builder.process(preKeyBundle);
						XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, address, bundle.getIdentityKey().getFingerprint().replaceAll("\\s", ""));
						sessions.put(address, session);
						fetchStatusMap.put(address, FetchStatus.SUCCESS);
					} catch (UntrustedIdentityException | InvalidKeyException e) {
						Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Error building session for " + address + ": "
								+ e.getClass().getName() + ", " + e.getMessage());
						fetchStatusMap.put(address, FetchStatus.ERROR);
					}

					finish();
				}
			});
		} catch (InvalidJidException e) {
			Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Got address with invalid jid: " + address.getName());
		}
	}

	public Set<AxolotlAddress> findDevicesWithoutSession(final Conversation conversation) {
		Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Finding devices without session for " + conversation.getContact().getJid().toBareJid());
		Jid contactJid = conversation.getContact().getJid().toBareJid();
		Set<AxolotlAddress> addresses = new HashSet<>();
		if (deviceIds.get(contactJid) != null) {
			for (Integer foreignId : this.deviceIds.get(contactJid)) {
				AxolotlAddress address = new AxolotlAddress(contactJid.toString(), foreignId);
				if (sessions.get(address) == null) {
					IdentityKey identityKey = axolotlStore.loadSession(address).getSessionState().getRemoteIdentityKey();
					if (identityKey != null) {
						Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already have session for " + address.toString() + ", adding to cache...");
						XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, address, identityKey.getFingerprint().replaceAll("\\s", ""));
						sessions.put(address, session);
					} else {
						Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Found device " + account.getJid().toBareJid() + ":" + foreignId);
						addresses.add(new AxolotlAddress(contactJid.toString(), foreignId));
					}
				}
			}
		} else {
			Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Have no target devices in PEP!");
		}
		if (deviceIds.get(account.getJid().toBareJid()) != null) {
			for (Integer ownId : this.deviceIds.get(account.getJid().toBareJid())) {
				AxolotlAddress address = new AxolotlAddress(account.getJid().toBareJid().toString(), ownId);
				if (sessions.get(address) == null) {
					IdentityKey identityKey = axolotlStore.loadSession(address).getSessionState().getRemoteIdentityKey();
					if (identityKey != null) {
						Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already have session for " + address.toString() + ", adding to cache...");
						XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, address, identityKey.getFingerprint().replaceAll("\\s", ""));
						sessions.put(address, session);
					} else {
						Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Found device " + account.getJid().toBareJid() + ":" + ownId);
						addresses.add(new AxolotlAddress(account.getJid().toBareJid().toString(), ownId));
					}
				}
			}
		}

		return addresses;
	}

	public boolean createSessionsIfNeeded(final Conversation conversation, final boolean flushWaitingQueueAfterFetch) {
		Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Creating axolotl sessions if needed...");
		boolean newSessions = false;
		Set<AxolotlAddress> addresses = findDevicesWithoutSession(conversation);
		for (AxolotlAddress address : addresses) {
			Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Processing device: " + address.toString());
			FetchStatus status = fetchStatusMap.get(address);
			if (status == null || status == FetchStatus.ERROR) {
				fetchStatusMap.put(address, FetchStatus.PENDING);
				this.buildSessionFromPEP(conversation, address, flushWaitingQueueAfterFetch);
				newSessions = true;
			} else {
				Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already fetching bundle for " + address.toString());
			}
		}

		return newSessions;
	}

	public boolean hasPendingKeyFetches(Conversation conversation) {
		AxolotlAddress ownAddress = new AxolotlAddress(account.getJid().toBareJid().toString(), 0);
		AxolotlAddress foreignAddress = new AxolotlAddress(conversation.getJid().toBareJid().toString(), 0);
		return fetchStatusMap.getAll(ownAddress).containsValue(FetchStatus.PENDING)
				|| fetchStatusMap.getAll(foreignAddress).containsValue(FetchStatus.PENDING);

	}

	@Nullable
	public XmppAxolotlMessage encrypt(Message message) {
		final String content;
		if (message.hasFileOnRemoteHost()) {
			content = message.getFileParams().url.toString();
		} else {
			content = message.getBody();
		}
		final XmppAxolotlMessage axolotlMessage;
		try {
			axolotlMessage = new XmppAxolotlMessage(message.getContact().getJid().toBareJid(),
					getOwnDeviceId(), content);
		} catch (CryptoFailedException e) {
			Log.w(Config.LOGTAG, getLogprefix(account) + "Failed to encrypt message: " + e.getMessage());
			return null;
		}

		if (findSessionsforContact(message.getContact()).isEmpty()) {
			return null;
		}
		Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Building axolotl foreign keyElements...");
		for (XmppAxolotlSession session : findSessionsforContact(message.getContact())) {
			Log.v(Config.LOGTAG, AxolotlService.getLogprefix(account) + session.getRemoteAddress().toString());
			axolotlMessage.addKeyElement(session.processSending(axolotlMessage.getInnerKey()));
		}
		Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Building axolotl own keyElements...");
		for (XmppAxolotlSession session : findOwnSessions()) {
			Log.v(Config.LOGTAG, AxolotlService.getLogprefix(account) + session.getRemoteAddress().toString());
			axolotlMessage.addKeyElement(session.processSending(axolotlMessage.getInnerKey()));
		}

		return axolotlMessage;
	}

	private void processSending(final Message message, final boolean delay) {
		executor.execute(new Runnable() {
			@Override
			public void run() {
				XmppAxolotlMessage axolotlMessage = encrypt(message);
				if (axolotlMessage == null) {
					mXmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED);
					//mXmppConnectionService.updateConversationUi();
				} else {
					Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Generated message, caching: " + message.getUuid());
					messageCache.put(message.getUuid(), axolotlMessage);
					mXmppConnectionService.resendMessage(message, delay);
				}
			}
		});
	}

	public void prepareMessage(final Message message, final boolean delay) {
		if (!messageCache.containsKey(message.getUuid())) {
			boolean newSessions = createSessionsIfNeeded(message.getConversation(), true);
			if (!newSessions) {
				this.processSending(message, delay);
			}
		}
	}

	public XmppAxolotlMessage fetchAxolotlMessageFromCache(Message message) {
		XmppAxolotlMessage axolotlMessage = messageCache.get(message.getUuid());
		if (axolotlMessage != null) {
			Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Cache hit: " + message.getUuid());
			messageCache.remove(message.getUuid());
		} else {
			Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Cache miss: " + message.getUuid());
		}
		return axolotlMessage;
	}

	public XmppAxolotlMessage.XmppAxolotlPlaintextMessage processReceiving(XmppAxolotlMessage message) {
		XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintextMessage = null;
		AxolotlAddress senderAddress = new AxolotlAddress(message.getFrom().toString(),
				message.getSenderDeviceId());

		boolean newSession = false;
		XmppAxolotlSession session = sessions.get(senderAddress);
		if (session == null) {
			Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Account: " + account.getJid() + " No axolotl session found while parsing received message " + message);
			IdentityKey identityKey = axolotlStore.loadSession(senderAddress).getSessionState().getRemoteIdentityKey();
			if (identityKey != null) {
				session = new XmppAxolotlSession(account, axolotlStore, senderAddress, identityKey.getFingerprint().replaceAll("\\s", ""));
			} else {
				session = new XmppAxolotlSession(account, axolotlStore, senderAddress);
			}
			newSession = true;
		}

		for (XmppAxolotlMessage.XmppAxolotlKeyElement keyElement : message.getKeyElements()) {
			if (keyElement.getRecipientDeviceId() == getOwnDeviceId()) {
				Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Found axolotl keyElement matching own device ID, processing...");
				byte[] payloadKey = session.processReceiving(keyElement);
				if (payloadKey != null) {
					Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Got payload key from axolotl keyElement. Decrypting message...");
					try {
						plaintextMessage = message.decrypt(session, payloadKey, session.getFingerprint());
					} catch (CryptoFailedException e) {
						Log.w(Config.LOGTAG, getLogprefix(account) + "Failed to decrypt message: " + e.getMessage());
						break;
					}
				}
				Integer preKeyId = session.getPreKeyId();
				if (preKeyId != null) {
					publishBundlesIfNeeded();
					session.resetPreKeyId();
				}
				break;
			}
		}

		if (newSession && plaintextMessage != null) {
			sessions.put(senderAddress, session);
		}

		return plaintextMessage;
	}
}
