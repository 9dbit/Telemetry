import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  FlatList,
  Modal,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import Telemetry from 'telemetry-ios-native';
import type { PeerSeenEvent, TelemetryIdentity } from 'telemetry-ios-native';

type Tab = 'Messages' | 'Nearby' | 'Network' | 'Settings';
type Peer = PeerSeenEvent & { lastSeen: number };
type ChatMessage = { id: string; text: string; mine: boolean; delivered?: boolean };

const C = {
  ink: '#07111F',
  card: '#0D1B2C',
  card2: '#12243A',
  blue: '#2F86FF',
  cyan: '#38E2C2',
  text: '#F4F8FF',
  muted: '#93A8C2',
};

export default function App() {
  const [tab, setTab] = useState<Tab>('Messages');
  const [identity, setIdentity] = useState<TelemetryIdentity | null>(null);
  const [peers, setPeers] = useState<Record<string, Peer>>({});
  const [running, setRunning] = useState(false);
  const [networkState, setNetworkState] = useState('Ready');
  const [verification, setVerification] = useState<{ peerId: string; deviceId: string; safetyCode: string } | null>(null);
  const [trustedPeer, setTrustedPeer] = useState<{ peerId: string; deviceId: string } | null>(null);
  const [chatOpen, setChatOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const capabilities = useMemo(() => Telemetry.getCapabilities(), []);

  useEffect(() => {
    setIdentity(Telemetry.getIdentity());
    const subscriptions = [
      Telemetry.addListener('onState', event => {
        setNetworkState(event.detail ? `${event.state} · ${event.detail}` : event.state);
        setRunning(!['stopped', 'ready'].includes(event.state));
      }),
      Telemetry.addListener('onPeerSeen', event => {
        setPeers(current => ({ ...current, [event.peerId]: { ...event, lastSeen: Date.now() } }));
      }),
      Telemetry.addListener('onVerification', event => setVerification(event)),
      Telemetry.addListener('onTrusted', event => {
        setVerification(null);
        setTrustedPeer(event);
        setChatOpen(true);
      }),
      Telemetry.addListener('onMessage', event => {
        setTrustedPeer({ peerId: event.peerId, deviceId: event.deviceId });
        setMessages(current => [...current, { id: event.messageId, text: event.text, mine: false, delivered: true }]);
      }),
      Telemetry.addListener('onDelivery', event => {
        setMessages(current => current.map(item => item.id === event.messageId ? { ...item, delivered: true } : item));
      }),
      Telemetry.addListener('onError', event => Alert.alert('Telemetry', event.message)),
    ];
    return () => subscriptions.forEach(subscription => subscription.remove());
  }, []);

  const peerList = Object.values(peers).sort((a, b) => b.rssi - a.rssi);

  async function toggleOffline() {
    try {
      if (running) {
        await Telemetry.stopOffline();
        setRunning(false);
        setNetworkState('Stopped');
      } else {
        await Telemetry.startOffline();
        setRunning(true);
      }
    } catch (error) {
      Alert.alert('Telemetry', String(error));
    }
  }

  async function sendText() {
    const text = draft.trim();
    if (!text || !trustedPeer) return;
    try {
      const id = await Telemetry.sendText(trustedPeer.peerId, text);
      setMessages(current => [...current, { id, text, mine: true, delivered: false }]);
      setDraft('');
    } catch (error) {
      Alert.alert('Could not send', String(error));
    }
  }

  return (
    <SafeAreaView style={styles.safe}>
      <View style={styles.header}>
        <View>
          <Text style={styles.brand}>Telemetry</Text>
          <Text style={styles.subtle}>iOS Preview · Offline-first</Text>
        </View>
        <View style={[styles.dot, { backgroundColor: running ? C.cyan : C.muted }]} />
      </View>

      <View style={styles.content}>
        {tab === 'Messages' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>Messages</Text>
            <Text style={styles.copy}>Trusted conversations stay on-device. Internet is optional.</Text>
            <View style={styles.pillRow}>
              <Pill label="Offline" />
              <Pill label="Encrypted" />
              <Pill label="No cloud required" />
            </View>
            {trustedPeer ? (
              <Pressable style={styles.card} onPress={() => setChatOpen(true)}>
                <Text style={styles.cardTitle}>{shortId(trustedPeer.deviceId)}</Text>
                <Text style={styles.cardCopy}>{messages.at(-1)?.text ?? 'Secure session ready'}</Text>
                <Text style={styles.route}>DIRECT · BLE</Text>
              </Pressable>
            ) : (
              <Empty title="No trusted conversations" copy="Open Nearby, start offline mode, connect to a Telemetry peer, then compare the safety code." />
            )}
            <Pressable
              style={styles.sos}
              delayLongPress={1800}
              onLongPress={() => Alert.alert('SOS Preview', 'Multi-hop SOS fan-out will be enabled after relay routing is implemented.')}
            >
              <Text style={styles.sosText}>Hold for SOS</Text>
            </Pressable>
          </ScrollView>
        )}

        {tab === 'Nearby' && (
          <View style={styles.page}>
            <Text style={styles.title}>Nearby</Text>
            <Text style={styles.copy}>CoreBluetooth discovery and GATT bootstrap. No SIM or internet required.</Text>
            <Pressable style={[styles.primary, running && styles.stop]} onPress={toggleOffline}>
              <Text style={styles.primaryText}>{running ? 'Stop Offline' : 'Start Offline'}</Text>
            </Pressable>
            <View style={styles.diagnostic}>
              <Text style={styles.cyanText}>{networkState}</Text>
              <Text style={styles.subtle}>Peers found: {peerList.length} · Advertising: {running ? 'ON' : 'OFF'}</Text>
            </View>
            <FlatList
              data={peerList}
              keyExtractor={item => item.peerId}
              style={styles.peerList}
              renderItem={({ item }) => (
                <Pressable style={styles.peerCard} onPress={() => Telemetry.connect(item.peerId)}>
                  <View style={styles.flex}>
                    <Text style={styles.cardTitle}>{item.name || 'Telemetry device'}</Text>
                    <Text style={styles.cardCopy}>{signalLabel(item.rssi)} · {item.rssi} dBm</Text>
                    <Text style={styles.mono}>{item.peerId.slice(0, 18)}…</Text>
                  </View>
                  <Text style={styles.link}>Connect</Text>
                </Pressable>
              )}
              ListEmptyComponent={
                <Empty
                  title={running ? 'Scanning for Telemetry devices…' : 'Discovery is stopped'}
                  copy={running ? 'Keep the other device close and open Telemetry there too.' : 'Tap Start Offline to advertise and scan simultaneously.'}
                />
              }
            />
          </View>
        )}

        {tab === 'Network' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>Network</Text>
            <Metric label="CoreBluetooth" value={capabilities.bluetooth ? 'AVAILABLE' : 'UNAVAILABLE'} />
            <Metric label="BLE GATT" value={running ? 'ACTIVE' : 'READY'} />
            <Metric label="Session" value={trustedPeer ? 'TRUSTED' : 'NOT CONNECTED'} />
            <Metric label="Identity" value="Ed25519" />
            <Metric label="Session key" value="Ephemeral X25519" />
            <Metric label="Payload" value="AES-256-GCM" />
            <Metric label="Wi-Fi Aware" value={capabilities.wifiAware ? 'SUPPORTED' : 'NEXT TRANSPORT'} />
            <Text style={styles.note}>{capabilities.wifiAwareReason}</Text>
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
            <Text style={styles.section}>Trial channel</Text>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Expo Development Build / EAS Internal</Text>
              <Text style={styles.cardCopy}>Expo Go is not used because Telemetry requires custom native CoreBluetooth and CryptoKit code.</Text>
            </View>
          </ScrollView>
        )}
      </View>

      <View style={styles.nav}>
        {(['Messages', 'Nearby', 'Network', 'Settings'] as Tab[]).map(item => (
          <Pressable key={item} style={styles.navItem} onPress={() => setTab(item)}>
            <Text style={[styles.navText, tab === item && styles.navActive]}>{item}</Text>
          </Pressable>
        ))}
      </View>

      <Modal visible={!!verification} transparent animationType="slide" onRequestClose={() => setVerification(null)}>
        <View style={styles.backdrop}>
          <View style={styles.sheet}>
            <Text style={styles.eyebrow}>VERIFY IDENTITY</Text>
            <Text style={styles.sheetTitle}>Compare safety codes</Text>
            <Text style={styles.code}>{verification?.safetyCode}</Text>
            <Text style={styles.copy}>Confirm this exact code is visible on the other Telemetry device.</Text>
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
          <View style={styles.chatHeader}>
            <Pressable onPress={() => setChatOpen(false)}><Text style={styles.link}>Close</Text></Pressable>
            <View style={styles.center}>
              <Text style={styles.cardTitle}>{trustedPeer ? shortId(trustedPeer.deviceId) : 'Telemetry peer'}</Text>
              <Text style={styles.route}>OFFLINE · ENCRYPTED · BLE</Text>
            </View>
            <View style={styles.headerSpacer} />
          </View>
          <FlatList
            data={messages}
            keyExtractor={item => item.id}
            contentContainerStyle={styles.messages}
            renderItem={({ item }) => (
              <View style={[styles.bubble, item.mine ? styles.mine : styles.theirs]}>
                <Text style={styles.bubbleText}>{item.text}</Text>
                {item.mine && <Text style={styles.receipt}>{item.delivered ? 'Delivered · signed receipt' : 'Sending…'}</Text>}
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
              maxLength={160}
            />
            <Pressable style={styles.send} onPress={sendText}><Text style={styles.primaryText}>Send</Text></Pressable>
          </View>
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
function shortId(value: string) { return value.replace('tlm:device:', 'TLM ').slice(0, 16); }
function signalLabel(rssi: number) { return rssi >= -60 ? 'Strong' : rssi >= -75 ? 'Fair' : 'Weak'; }

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: C.ink },
  flex: { flex: 1 },
  center: { alignItems: 'center' },
  header: { height: 72, paddingHorizontal: 20, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#21354C' },
  brand: { color: C.text, fontWeight: '800', fontSize: 24 },
  subtle: { color: C.muted, fontSize: 11, marginTop: 3 },
  dot: { width: 11, height: 11, borderRadius: 99 },
  content: { flex: 1 },
  page: { flex: 1, padding: 20, paddingBottom: 28 },
  title: { color: C.text, fontSize: 34, fontWeight: '800', marginBottom: 8 },
  copy: { color: C.muted, fontSize: 14, lineHeight: 20 },
  pillRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginVertical: 18 },
  pill: { paddingHorizontal: 12, paddingVertical: 7, borderRadius: 99, backgroundColor: C.card2, borderWidth: 1, borderColor: '#1E725F' },
  pillText: { color: C.cyan, fontSize: 11, fontWeight: '700' },
  card: { backgroundColor: C.card, borderRadius: 18, padding: 16, borderWidth: 1, borderColor: '#17304B' },
  cardTitle: { color: C.text, fontWeight: '700', fontSize: 16 },
  cardCopy: { color: C.muted, marginTop: 5, fontSize: 12, lineHeight: 17 },
  route: { color: C.cyan, fontSize: 9, fontWeight: '800', marginTop: 8 },
  sos: { marginTop: 24, alignSelf: 'flex-end', backgroundColor: '#A91E31', borderRadius: 99, paddingHorizontal: 20, paddingVertical: 13 },
  sosText: { color: '#FFF', fontWeight: '800' },
  primary: { marginTop: 18, height: 52, borderRadius: 16, backgroundColor: C.blue, alignItems: 'center', justifyContent: 'center' },
  stop: { backgroundColor: '#6E2634' },
  primaryText: { color: '#FFF', fontWeight: '800', fontSize: 15 },
  diagnostic: { marginTop: 14, backgroundColor: C.card2, borderRadius: 16, padding: 14 },
  cyanText: { color: C.cyan, fontWeight: '700' },
  peerList: { marginTop: 14 },
  peerCard: { backgroundColor: C.card, borderRadius: 18, padding: 15, marginBottom: 10, flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: '#17304B' },
  mono: { color: '#67809E', fontSize: 10, marginTop: 5, fontFamily: 'Menlo' },
  link: { color: C.blue, fontWeight: '800', fontSize: 13 },
  empty: { marginTop: 16, backgroundColor: C.card, borderRadius: 18, padding: 18, borderWidth: 1, borderColor: '#17304B' },
  metric: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 15, borderBottomColor: '#1B3047', borderBottomWidth: StyleSheet.hairlineWidth },
  metricLabel: { color: C.muted },
  metricValue: { color: C.cyan, fontWeight: '800', fontSize: 12 },
  note: { color: C.muted, fontSize: 11, marginTop: 15, lineHeight: 17 },
  section: { color: C.muted, fontWeight: '800', marginTop: 20, marginBottom: 8, textTransform: 'uppercase', fontSize: 11, letterSpacing: 1 },
  nav: { height: 66, flexDirection: 'row', backgroundColor: '#091522', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: '#21354C' },
  navItem: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  navText: { color: C.muted, fontSize: 11, fontWeight: '700' },
  navActive: { color: C.cyan },
  backdrop: { flex: 1, backgroundColor: '#000A', justifyContent: 'flex-end' },
  sheet: { backgroundColor: '#0B1828', padding: 24, paddingBottom: 38, borderTopLeftRadius: 28, borderTopRightRadius: 28 },
  eyebrow: { color: C.cyan, letterSpacing: 1.5, fontSize: 10, fontWeight: '800' },
  sheetTitle: { color: C.text, fontSize: 25, fontWeight: '800', marginTop: 5 },
  code: { color: C.text, fontFamily: 'Menlo', fontSize: 42, fontWeight: '800', textAlign: 'center', marginVertical: 26, letterSpacing: 3 },
  secondary: { height: 48, alignItems: 'center', justifyContent: 'center', marginTop: 6 },
  chatHeader: { height: 68, paddingHorizontal: 18, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#21354C' },
  headerSpacer: { width: 44 },
  messages: { padding: 16, gap: 10 },
  bubble: { maxWidth: '82%', paddingHorizontal: 14, paddingVertical: 11, borderRadius: 18 },
  mine: { alignSelf: 'flex-end', backgroundColor: '#155DB0' },
  theirs: { alignSelf: 'flex-start', backgroundColor: C.card2 },
  bubbleText: { color: '#FFF', fontSize: 15 },
  receipt: { color: '#BBD5F2', fontSize: 9, marginTop: 6 },
  composer: { padding: 12, flexDirection: 'row', gap: 8, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: '#21354C' },
  input: { flex: 1, minHeight: 48, backgroundColor: C.card2, color: C.text, borderRadius: 16, paddingHorizontal: 14 },
  send: { width: 70, borderRadius: 16, backgroundColor: C.blue, alignItems: 'center', justifyContent: 'center' },
});
