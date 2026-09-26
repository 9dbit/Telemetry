import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  FlatList,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SymbolView } from 'expo-symbols';
import Telemetry from 'telemetry-ios-native';
import type { PeerSeenEvent, TelemetryIdentity } from 'telemetry-ios-native';

type Tab = 'Chats' | 'Nearby' | 'Network' | 'SOS' | 'Settings';
type Peer = PeerSeenEvent & { lastSeen: number };
type DeliveryState = 'queued' | 'finding' | 'connecting' | 'sending' | 'delivered' | 'retry';
type ChatMessage = {
  id: string;
  transportId?: string;
  text: string;
  mine: boolean;
  state?: DeliveryState;
};
type TrustedPeer = { peerId: string; deviceId: string };
type PendingIntent = { localId: string; peerId: string; text: string; transportId?: string };

const C = {
  ink: '#07111F',
  card: '#0D1B2C',
  card2: '#12243A',
  blue: '#2F86FF',
  cyan: '#38E2C2',
  text: '#F4F8FF',
  muted: '#93A8C2',
  line: '#21354C',
  danger: '#D53A4F',
};

const NAV: { tab: Tab; icon: string }[] = [
  { tab: 'Chats', icon: 'message.fill' },
  { tab: 'Nearby', icon: 'dot.radiowaves.left.and.right' },
  { tab: 'Network', icon: 'network' },
  { tab: 'SOS', icon: 'sos.circle.fill' },
  { tab: 'Settings', icon: 'gearshape.fill' },
];

export default function App() {
  const [tab, setTab] = useState<Tab>('Chats');
  const [identity, setIdentity] = useState<TelemetryIdentity | null>(null);
  const [peers, setPeers] = useState<Record<string, Peer>>({});
  const [running, setRunning] = useState(false);
  const [networkState, setNetworkState] = useState('Ready');
  const [verification, setVerification] = useState<{ peerId: string; deviceId: string; safetyCode: string } | null>(null);
  const [trustedPeer, setTrustedPeer] = useState<TrustedPeer | null>(null);
  const [sessionReady, setSessionReady] = useState(false);
  const [chatOpen, setChatOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const capabilities = useMemo(() => Telemetry.getCapabilities(), []);

  const runningRef = useRef(false);
  const peersRef = useRef<Record<string, Peer>>({});
  const trustedPeerRef = useRef<TrustedPeer | null>(null);
  const pendingRef = useRef<Record<string, PendingIntent>>({});
  const connectingRef = useRef<Set<string>>(new Set());

  useEffect(() => { runningRef.current = running; }, [running]);
  useEffect(() => { peersRef.current = peers; }, [peers]);
  useEffect(() => { trustedPeerRef.current = trustedPeer; }, [trustedPeer]);

  useEffect(() => {
    setIdentity(Telemetry.getIdentity());

    const subscriptions = [
      Telemetry.addListener('onState', event => {
        setNetworkState(event.detail ? `${event.state} · ${event.detail}` : event.state);
        const active = !['stopped', 'ready'].includes(event.state);
        setRunning(active);
        runningRef.current = active;

        if (event.state === 'disconnected') {
          setSessionReady(false);
          if (event.detail) connectingRef.current.delete(event.detail);
          markPeerPending(event.detail, 'finding');
        }
      }),
      Telemetry.addListener('onPeerSeen', event => {
        const next = { ...peersRef.current, [event.peerId]: { ...event, lastSeen: Date.now() } };
        peersRef.current = next;
        setPeers(next);
        if (hasPendingForPeer(event.peerId)) void ensureConnection(event.peerId);
      }),
      Telemetry.addListener('onVerification', event => {
        connectingRef.current.delete(event.peerId);
        setVerification(event);
      }),
      Telemetry.addListener('onTrusted', event => {
        connectingRef.current.delete(event.peerId);
        const peer = { peerId: event.peerId, deviceId: event.deviceId };
        trustedPeerRef.current = peer;
        setTrustedPeer(peer);
        setVerification(null);
        setSessionReady(true);
        setChatOpen(true);
        void flushPending(event.peerId);
      }),
      Telemetry.addListener('onMessage', event => {
        const peer = { peerId: event.peerId, deviceId: event.deviceId };
        trustedPeerRef.current = peer;
        setTrustedPeer(peer);
        setSessionReady(true);
        setMessages(current => [...current, {
          id: event.messageId,
          text: event.text,
          mine: false,
          state: 'delivered',
        }]);
      }),
      Telemetry.addListener('onDelivery', event => {
        let localId: string | undefined;
        for (const intent of Object.values(pendingRef.current)) {
          if (intent.transportId === event.messageId) {
            localId = intent.localId;
            break;
          }
        }
        if (localId) delete pendingRef.current[localId];
        setMessages(current => current.map(item =>
          item.transportId === event.messageId ? { ...item, state: 'delivered' } : item
        ));
      }),
      Telemetry.addListener('onError', event => {
        if (isRecoverableTransportError(event.message)) return;
        Alert.alert('Telemetry', event.message);
      }),
    ];

    void Telemetry.startOffline().catch(() => {
      setNetworkState('Radio paused');
    });

    return () => subscriptions.forEach(subscription => subscription.remove());
  }, []);

  const peerList = Object.values(peers).sort((a, b) => b.rssi - a.rssi);
  const routeLabel = sessionReady
    ? 'OFFLINE · ENCRYPTED · BLE'
    : networkState.startsWith('connecting') || networkState.startsWith('reconnecting')
      ? 'CONNECTING · ENCRYPTED'
      : 'QUEUED · AUTO-ROUTE READY';

  function hasPendingForPeer(peerId: string) {
    return Object.values(pendingRef.current).some(item => item.peerId === peerId && !item.transportId);
  }

  function markPeerPending(peerId: string | undefined, state: DeliveryState) {
    if (!peerId) return;
    const ids = new Set(Object.values(pendingRef.current)
      .filter(item => item.peerId === peerId && !item.transportId)
      .map(item => item.localId));
    if (!ids.size) return;
    setMessages(current => current.map(item => ids.has(item.id) ? { ...item, state } : item));
  }

  function setMessageState(localId: string, state: DeliveryState, transportId?: string) {
    setMessages(current => current.map(item =>
      item.id === localId ? { ...item, state, transportId: transportId ?? item.transportId } : item
    ));
  }

  async function ensureRadio() {
    if (runningRef.current) return;
    await Telemetry.startOffline();
    runningRef.current = true;
    setRunning(true);
  }

  async function ensureConnection(peerId: string) {
    if (connectingRef.current.has(peerId)) return;
    connectingRef.current.add(peerId);
    markPeerPending(peerId, peersRef.current[peerId] ? 'connecting' : 'finding');

    try {
      await ensureRadio();
      await Telemetry.connect(peerId);
      setTimeout(() => connectingRef.current.delete(peerId), 6000);
    } catch {
      connectingRef.current.delete(peerId);
      markPeerPending(peerId, 'finding');
    }
  }

  async function attemptDelivery(localId: string) {
    const intent = pendingRef.current[localId];
    if (!intent || intent.transportId) return;

    try {
      await ensureRadio();
      setMessageState(localId, 'sending');
      const transportId = await Telemetry.sendText(intent.peerId, intent.text);
      pendingRef.current[localId] = { ...intent, transportId };
      setMessageState(localId, 'sending', transportId);
    } catch (error) {
      if (isRecoverableTransportError(String(error))) {
        setMessageState(localId, peersRef.current[intent.peerId] ? 'connecting' : 'finding');
        await ensureConnection(intent.peerId);
        return;
      }
      setMessageState(localId, 'retry');
    }
  }

  async function flushPending(peerId: string) {
    const intents = Object.values(pendingRef.current)
      .filter(item => item.peerId === peerId && !item.transportId);
    for (const intent of intents) await attemptDelivery(intent.localId);
  }

  async function toggleOffline() {
    try {
      if (runningRef.current) {
        await Telemetry.stopOffline();
        runningRef.current = false;
        setRunning(false);
        setSessionReady(false);
        setNetworkState('Radio paused');
      } else {
        await ensureRadio();
      }
    } catch (error) {
      Alert.alert('Telemetry', String(error));
    }
  }

  async function openNearbyPeer(peer: Peer) {
    if (trustedPeerRef.current?.peerId === peer.peerId) {
      setChatOpen(true);
      return;
    }
    try {
      await ensureRadio();
      await Telemetry.connect(peer.peerId);
    } catch (error) {
      Alert.alert('Telemetry', String(error));
    }
  }

  function sendText() {
    const text = draft.trim();
    const peer = trustedPeerRef.current;
    if (!text || !peer) return;

    const localId = `local:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`;
    pendingRef.current[localId] = { localId, peerId: peer.peerId, text };
    setMessages(current => [...current, { id: localId, text, mine: true, state: 'queued' }]);
    setDraft('');
    void attemptDelivery(localId);
  }

  return (
    <SafeAreaView style={styles.safe}>
      <View style={styles.header}>
        <View>
          <Text style={styles.brand}>Telemetry</Text>
          <Text style={styles.subtle}>Offline-first · adaptive transport</Text>
        </View>
        <View style={[styles.dot, { backgroundColor: running ? C.cyan : C.muted }]} />
      </View>

      <View style={styles.content}>
        {tab === 'Chats' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>Chats</Text>
            <Text style={styles.copy}>Send normally. Telemetry finds the route, reconnects and delivers in the background.</Text>
            <View style={styles.pillRow}>
              <Pill label="Encrypted" />
              <Pill label="Auto-route" />
              <Pill label="No cloud required" />
            </View>
            {trustedPeer ? (
              <Pressable style={styles.card} onPress={() => setChatOpen(true)}>
                <View style={styles.rowBetween}>
                  <View style={styles.flex}>
                    <Text style={styles.cardTitle}>{shortId(trustedPeer.deviceId)}</Text>
                    <Text style={styles.cardCopy}>{messages.at(-1)?.text ?? 'Trusted peer · ready when reachable'}</Text>
                  </View>
                  <StatusDot active={sessionReady} />
                </View>
                <Text style={styles.route}>{routeLabel}</Text>
              </Pressable>
            ) : (
              <Empty
                title="No trusted conversations"
                copy="Open Nearby and verify a device once. After that, reconnects happen automatically when you send."
              />
            )}
          </ScrollView>
        )}

        {tab === 'Nearby' && (
          <View style={styles.page}>
            <Text style={styles.title}>Nearby</Text>
            <Text style={styles.copy}>Discovery runs automatically while Telemetry is active. Manual connection is only needed for first-time identity verification.</Text>
            <View style={styles.diagnostic}>
              <View style={styles.rowBetween}>
                <View style={styles.flex}>
                  <Text style={styles.cyanText}>{running ? 'Offline radio active' : 'Offline radio paused'}</Text>
                  <Text style={styles.subtle}>Peers: {peerList.length} · {networkState}</Text>
                </View>
                <Pressable style={styles.miniButton} onPress={toggleOffline}>
                  <Text style={styles.miniButtonText}>{running ? 'Pause' : 'Resume'}</Text>
                </Pressable>
              </View>
            </View>
            <FlatList
              data={peerList}
              keyExtractor={item => item.peerId}
              style={styles.peerList}
              renderItem={({ item }) => {
                const isTrusted = trustedPeer?.peerId === item.peerId;
                return (
                  <Pressable style={styles.peerCard} onPress={() => void openNearbyPeer(item)}>
                    <View style={styles.signalIcon}>
                      <SymbolView name={'antenna.radiowaves.left.and.right' as any} size={21} tintColor={C.cyan} />
                    </View>
                    <View style={styles.flex}>
                      <Text style={styles.cardTitle}>{item.name || 'Telemetry device'}</Text>
                      <Text style={styles.cardCopy}>{signalLabel(item.rssi)} · {item.rssi} dBm · {isTrusted ? 'Trusted' : 'New identity'}</Text>
                      <Text style={styles.mono}>{item.peerId.slice(0, 18)}…</Text>
                    </View>
                    <Text style={styles.link}>{isTrusted ? 'Open' : 'Verify'}</Text>
                  </Pressable>
                );
              }}
              ListEmptyComponent={
                <Empty
                  title={running ? 'Scanning for Telemetry devices…' : 'Offline radio paused'}
                  copy={running ? 'Keep the other Telemetry device nearby. Discovery and reconnect are automatic.' : 'Resume the radio to discover nearby peers.'}
                />
              }
            />
          </View>
        )}

        {tab === 'Network' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>Network</Text>
            <Text style={styles.copy}>Telemetry chooses the best available route. BLE is live today; longer-range transports plug into the same delivery engine.</Text>
            <TransportCard icon="dot.radiowaves.left.and.right" title="Bluetooth LE" detail="Discovery · bootstrap · direct chat" status={running ? 'ACTIVE' : 'READY'} active />
            <TransportCard icon="wifi" title="Wi‑Fi" detail="Automatic high-bandwidth transport upgrade" status={capabilities.wifiAware ? 'SUPPORTED' : 'NEXT'} />
            <TransportCard icon="point.3.connected.trianglepath.dotted" title="Mesh Relay" detail="Store-and-forward · multi-hop routing" status="PLANNED" />
            <TransportCard icon="antenna.radiowaves.left.and.right" title="LoRa Gateway" detail="Long-range text · SOS · coordinates" status="NODE" />
            <TransportCard icon="network.badge.shield.half.filled" title="Satellite Gateway" detail="Remote backhaul · emergency escalation" status="GATEWAY" />
            <Text style={styles.section}>Secure session</Text>
            <Metric label="Session" value={sessionReady ? 'TRUSTED · ACTIVE' : trustedPeer ? 'TRUSTED · IDLE' : 'NO PEER'} />
            <Metric label="Identity" value="Ed25519" />
            <Metric label="Session key" value="Ephemeral X25519" />
            <Metric label="Payload" value="AES-256-GCM" />
          </ScrollView>
        )}

        {tab === 'SOS' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>SOS</Text>
            <Text style={styles.copy}>Emergency traffic will receive route priority across BLE, relay nodes, LoRa and satellite gateways as those transports come online.</Text>
            <View style={styles.sosPanel}>
              <SymbolView name={'sos.circle.fill' as any} size={48} tintColor="#FF5A6E" />
              <Text style={styles.sosTitle}>Emergency relay</Text>
              <Text style={styles.cardCopy}>Current preview sends only to an active trusted direct peer. Multi-hop fan-out is a later milestone.</Text>
              <Pressable
                style={styles.sosButton}
                delayLongPress={1800}
                onLongPress={() => Alert.alert('SOS Preview', 'Priority fan-out will be enabled after relay routing is implemented.')}
              >
                <Text style={styles.sosText}>Hold to send SOS</Text>
              </Pressable>
            </View>
          </ScrollView>
        )}

        {tab === 'Settings' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>Settings</Text>
            <Text style={styles.section}>Device identity</Text>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>{identity ? shortId(identity.deviceId) : 'Loading identity…'}</Text>
              <Text style={styles.mono}>{identity?.deviceId}</Text>
              <Text style={styles.cardCopy}>Private identity material remains in the iPhone Keychain-backed app container.</Text>
            </View>
            <Text style={styles.section}>Connection behavior</Text>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Automatic</Text>
              <Text style={styles.cardCopy}>Users send messages; Telemetry handles discovery, reconnect and secure transport in the background.</Text>
            </View>
          </ScrollView>
        )}
      </View>

      <View style={styles.nav}>
        {NAV.map(item => {
          const active = tab === item.tab;
          return (
            <Pressable key={item.tab} style={styles.navItem} onPress={() => setTab(item.tab)}>
              <SymbolView name={item.icon as any} size={23} tintColor={active ? C.cyan : C.muted} />
              <Text style={[styles.navText, active && styles.navActive]}>{item.tab}</Text>
            </Pressable>
          );
        })}
      </View>

      <Modal visible={!!verification} transparent animationType="slide" onRequestClose={() => setVerification(null)}>
        <View style={styles.backdrop}>
          <View style={styles.sheet}>
            <Text style={styles.eyebrow}>VERIFY IDENTITY</Text>
            <Text style={styles.sheetTitle}>Compare safety codes</Text>
            <Text style={styles.code}>{verification?.safetyCode}</Text>
            <Text style={styles.copy}>Confirm this exact code is visible on the other Telemetry device. You only need to do this again if its identity key changes.</Text>
            <Pressable style={styles.primary} onPress={async () => {
              if (!verification) return;
              const ok = await Telemetry.trustPeer(verification.deviceId);
              if (!ok) Alert.alert('Telemetry', 'Could not trust this session.');
            }}>
              <Text style={styles.primaryText}>Codes match · Trust peer</Text>
            </Pressable>
            <Pressable style={styles.secondary} onPress={() => setVerification(null)}>
              <Text style={styles.subtle}>Cancel</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      <Modal visible={chatOpen && !!trustedPeer} animationType="slide" onRequestClose={() => setChatOpen(false)}>
        <SafeAreaView style={styles.safe}>
          <KeyboardAvoidingView style={styles.safe} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
            <View style={styles.chatHeader}>
              <Pressable onPress={() => setChatOpen(false)}><Text style={styles.link}>Close</Text></Pressable>
              <View style={styles.center}>
                <Text style={styles.cardTitle}>{trustedPeer ? shortId(trustedPeer.deviceId) : 'Telemetry peer'}</Text>
                <Text style={styles.route}>{routeLabel}</Text>
              </View>
              <View style={styles.headerSpacer} />
            </View>
            <FlatList
              data={messages}
              keyExtractor={item => item.id}
              contentContainerStyle={styles.messages}
              keyboardDismissMode="interactive"
              keyboardShouldPersistTaps="handled"
              maintainVisibleContentPosition={{ minIndexForVisible: 0 }}
              renderItem={({ item }) => (
                <View style={[styles.bubble, item.mine ? styles.mine : styles.theirs]}>
                  <Text style={styles.bubbleText}>{item.text}</Text>
                  {item.mine && <Text style={styles.receipt}>{deliveryLabel(item.state)}</Text>}
                </View>
              )}
            />
            <View style={styles.composer}>
              <TextInput
                value={draft}
                onChangeText={setDraft}
                placeholder="Message offline…"
                placeholderTextColor={C.muted}
                style={styles.input}
                multiline
                maxLength={160}
                blurOnSubmit={false}
              />
              <Pressable style={[styles.send, !draft.trim() && styles.sendDisabled]} onPress={sendText} disabled={!draft.trim()}>
                <SymbolView name={'arrow.up' as any} size={21} tintColor="#FFF" weight="bold" />
              </Pressable>
            </View>
          </KeyboardAvoidingView>
        </SafeAreaView>
      </Modal>
    </SafeAreaView>
  );
}

function Pill({ label }: { label: string }) {
  return <View style={styles.pill}><Text style={styles.pillText}>{label}</Text></View>;
}

function Empty({ title, copy }: { title: string; copy: string }) {
  return <View style={styles.empty}><Text style={styles.cardTitle}>{title}</Text><Text style={styles.cardCopy}>{copy}</Text></View>;
}

function Metric({ label, value }: { label: string; value: string }) {
  return <View style={styles.metric}><Text style={styles.metricLabel}>{label}</Text><Text style={styles.metricValue}>{value}</Text></View>;
}

function StatusDot({ active }: { active: boolean }) {
  return <View style={[styles.statusDot, { backgroundColor: active ? C.cyan : '#60758E' }]} />;
}

function TransportCard({ icon, title, detail, status, active = false }: { icon: string; title: string; detail: string; status: string; active?: boolean }) {
  return (
    <View style={[styles.transportCard, active && styles.transportActive]}>
      <View style={styles.transportIcon}><SymbolView name={icon as any} size={23} tintColor={active ? C.cyan : C.muted} /></View>
      <View style={styles.flex}>
        <Text style={styles.cardTitle}>{title}</Text>
        <Text style={styles.cardCopy}>{detail}</Text>
      </View>
      <Text style={[styles.transportStatus, active && styles.transportStatusActive]}>{status}</Text>
    </View>
  );
}

function deliveryLabel(state?: DeliveryState) {
  switch (state) {
    case 'delivered': return 'Delivered · signed receipt';
    case 'sending': return 'Sending · encrypted';
    case 'connecting': return 'Connecting securely…';
    case 'finding': return 'Finding peer…';
    case 'retry': return 'Retry pending';
    default: return 'Queued · waiting for route';
  }
}

function isRecoverableTransportError(value: string) {
  return /BLE connection|connection is not active|secure peer session|no longer available|characteristic is unavailable|transmit queue is busy|not connected/i.test(value);
}

function shortId(value: string) { return value.replace('tlm:device:', 'TLM ').slice(0, 16); }
function signalLabel(rssi: number) { return rssi >= -60 ? 'Strong' : rssi >= -75 ? 'Fair' : 'Weak'; }

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: C.ink },
  flex: { flex: 1 },
  center: { alignItems: 'center' },
  rowBetween: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  header: { height: 72, paddingHorizontal: 20, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: C.line },
  brand: { color: C.text, fontWeight: '800', fontSize: 24 },
  subtle: { color: C.muted, fontSize: 12, marginTop: 3 },
  dot: { width: 11, height: 11, borderRadius: 99 },
  statusDot: { width: 9, height: 9, borderRadius: 99 },
  content: { flex: 1 },
  page: { flexGrow: 1, padding: 20, paddingBottom: 28 },
  title: { color: C.text, fontSize: 34, fontWeight: '800', marginBottom: 8 },
  copy: { color: C.muted, fontSize: 14, lineHeight: 20 },
  pillRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginVertical: 18 },
  pill: { paddingHorizontal: 12, paddingVertical: 7, borderRadius: 99, backgroundColor: C.card2, borderWidth: 1, borderColor: '#1E725F' },
  pillText: { color: C.cyan, fontSize: 11, fontWeight: '700' },
  card: { backgroundColor: C.card, borderRadius: 18, padding: 16, borderWidth: 1, borderColor: '#17304B' },
  cardTitle: { color: C.text, fontWeight: '700', fontSize: 16 },
  cardCopy: { color: C.muted, marginTop: 5, fontSize: 12, lineHeight: 17 },
  route: { color: C.cyan, fontSize: 10, fontWeight: '800', marginTop: 8, letterSpacing: 0.35 },
  primary: { marginTop: 18, minHeight: 52, borderRadius: 16, backgroundColor: C.blue, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  primaryText: { color: '#FFF', fontWeight: '800', fontSize: 15 },
  diagnostic: { marginTop: 16, backgroundColor: C.card2, borderRadius: 16, padding: 14 },
  cyanText: { color: C.cyan, fontWeight: '700', fontSize: 14 },
  miniButton: { minWidth: 70, height: 36, borderRadius: 12, backgroundColor: '#18334F', alignItems: 'center', justifyContent: 'center', paddingHorizontal: 12 },
  miniButtonText: { color: C.text, fontWeight: '700', fontSize: 12 },
  peerList: { marginTop: 14 },
  peerCard: { backgroundColor: C.card, borderRadius: 18, padding: 15, marginBottom: 10, flexDirection: 'row', alignItems: 'center', gap: 12, borderWidth: 1, borderColor: '#17304B' },
  signalIcon: { width: 42, height: 42, borderRadius: 14, alignItems: 'center', justifyContent: 'center', backgroundColor: '#102B3A' },
  mono: { color: '#67809E', fontSize: 10, marginTop: 5, fontFamily: 'Menlo' },
  link: { color: C.blue, fontWeight: '800', fontSize: 13 },
  empty: { marginTop: 16, backgroundColor: C.card, borderRadius: 18, padding: 18, borderWidth: 1, borderColor: '#17304B' },
  metric: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 15, borderBottomColor: '#1B3047', borderBottomWidth: StyleSheet.hairlineWidth },
  metricLabel: { color: C.muted },
  metricValue: { color: C.cyan, fontWeight: '800', fontSize: 12 },
  section: { color: C.muted, fontWeight: '800', marginTop: 22, marginBottom: 8, textTransform: 'uppercase', fontSize: 11, letterSpacing: 1 },
  transportCard: { marginTop: 10, minHeight: 76, padding: 14, borderRadius: 18, backgroundColor: C.card, borderWidth: 1, borderColor: '#17304B', flexDirection: 'row', alignItems: 'center', gap: 12 },
  transportActive: { borderColor: '#1D725F' },
  transportIcon: { width: 42, height: 42, borderRadius: 13, alignItems: 'center', justifyContent: 'center', backgroundColor: '#102337' },
  transportStatus: { color: C.muted, fontSize: 10, fontWeight: '800' },
  transportStatusActive: { color: C.cyan },
  sosPanel: { marginTop: 22, padding: 22, alignItems: 'center', backgroundColor: C.card, borderRadius: 24, borderWidth: 1, borderColor: '#5B2430' },
  sosTitle: { color: C.text, fontSize: 22, fontWeight: '800', marginTop: 12 },
  sosButton: { marginTop: 24, minHeight: 58, alignSelf: 'stretch', backgroundColor: '#A91E31', borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  sosText: { color: '#FFF', fontWeight: '800', fontSize: 15 },
  nav: { minHeight: 76, paddingTop: 8, paddingBottom: 5, flexDirection: 'row', backgroundColor: '#091522', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.line },
  navItem: { flex: 1, minHeight: 58, alignItems: 'center', justifyContent: 'center', gap: 4 },
  navText: { color: C.muted, fontSize: 12, fontWeight: '600' },
  navActive: { color: C.cyan, fontWeight: '800' },
  backdrop: { flex: 1, backgroundColor: '#000A', justifyContent: 'flex-end' },
  sheet: { backgroundColor: '#0B1828', padding: 24, paddingBottom: 38, borderTopLeftRadius: 28, borderTopRightRadius: 28 },
  eyebrow: { color: C.cyan, letterSpacing: 1.5, fontSize: 10, fontWeight: '800' },
  sheetTitle: { color: C.text, fontSize: 25, fontWeight: '800', marginTop: 5 },
  code: { color: C.text, fontFamily: 'Menlo', fontSize: 42, fontWeight: '800', textAlign: 'center', marginVertical: 26, letterSpacing: 3 },
  secondary: { height: 48, alignItems: 'center', justifyContent: 'center', marginTop: 6 },
  chatHeader: { height: 68, paddingHorizontal: 18, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: C.line },
  headerSpacer: { width: 44 },
  messages: { padding: 16, paddingBottom: 22, gap: 10 },
  bubble: { maxWidth: '82%', paddingHorizontal: 14, paddingVertical: 11, borderRadius: 18 },
  mine: { alignSelf: 'flex-end', backgroundColor: '#155DB0' },
  theirs: { alignSelf: 'flex-start', backgroundColor: C.card2 },
  bubbleText: { color: '#FFF', fontSize: 16, lineHeight: 21 },
  receipt: { color: '#BBD5F2', fontSize: 10, marginTop: 6 },
  composer: { paddingHorizontal: 12, paddingTop: 10, paddingBottom: 10, flexDirection: 'row', alignItems: 'flex-end', gap: 8, backgroundColor: '#091522', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.line },
  input: { flex: 1, minHeight: 48, maxHeight: 120, backgroundColor: C.card2, color: C.text, borderRadius: 20, paddingHorizontal: 16, paddingTop: 13, paddingBottom: 13, fontSize: 16 },
  send: { width: 48, height: 48, borderRadius: 24, backgroundColor: C.blue, alignItems: 'center', justifyContent: 'center' },
  sendDisabled: { opacity: 0.38 },
});
