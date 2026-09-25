import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  AppState,
  FlatList,
  KeyboardAvoidingView,
  Image,
  Modal,
  PanResponder,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { SymbolView } from 'expo-symbols';
import { BlurView } from 'expo-blur';
import * as ImagePicker from 'expo-image-picker';
import * as DocumentPicker from 'expo-document-picker';
import Telemetry from 'telemetry-ios-native';
import type { AppearanceMode, Contact, LocalProfile, LocalState, MediaEvent, PeerSeenEvent, TelemetryIdentity } from 'telemetry-ios-native';

type Tab = 'Social' | 'Calls' | 'Nearby' | 'Chats' | 'SOS' | 'Settings';
type Peer = PeerSeenEvent & { lastSeen: number };
type DeliveryState = 'queued' | 'finding' | 'connecting' | 'sending' | 'delivered' | 'retry';
type ChatMessage = {
  id: string;
  transportId?: string;
  text: string;
  mine: boolean;
  state?: DeliveryState;
  peerDeviceId?: string;
  timestamp?: number;
};
type TrustedPeer = { peerId: string; deviceId: string };
type TestTransport = 'auto' | 'ble' | 'wifi';
type PendingIntent = { localId: string; peerId: string; text: string; transportId?: string; attemptCount?: number; nextAttemptAt?: number; testTransport?: TestTransport };
type ReliabilityDiagnostics = {
  messageRecords: number;
  pendingOutgoing: number;
  deliveredOutgoing: number;
  incoming: number;
  duplicateMessageIds: number;
  acceptedIncoming: number;
  duplicateFramesSuppressed: number;
  replayRejected: number;
  deliveryReceiptsAccepted: number;
};
type ReliabilityRun = { batchId: string; total: number; delivered: number; startedAt: number; status: 'queueing' | 'running' | 'complete' | 'failed' };
type MediaTransferView = MediaEvent & { updatedAt: number };

const DARK_COLORS = {
  ink: '#000000',
  card: 'rgba(13,22,34,0.82)',
  card2: 'rgba(20,34,52,0.72)',
  blue: '#2F86FF',
  cyan: '#2F86FF',
  text: '#F7FAFF',
  muted: '#91A6BF',
  line: '#1E3045',
  danger: '#D53A4F',
  glass: 'rgba(21,39,59,0.46)',
  glassStrong: 'rgba(27,52,79,0.64)',
  field: '#000000',
};

const LIGHT_COLORS: ThemeColors = {
  ink: '#F5F7FA',
  card: 'rgba(255,255,255,0.88)',
  card2: 'rgba(232,238,245,0.82)',
  blue: '#126BDF',
  cyan: '#126BDF',
  text: '#0B1522',
  muted: '#607186',
  line: '#CCD7E3',
  danger: '#C8354A',
  glass: 'rgba(255,255,255,0.58)',
  glassStrong: 'rgba(255,255,255,0.78)',
  field: '#EAF0F6',
};

type ThemeColors = typeof DARK_COLORS;
let C: ThemeColors = DARK_COLORS;
let styles = createStyles(C);

const GLASS_NOISE = require('./assets/ui/glass-noise.png');

const NAV: { tab: Tab; icon: string }[] = [
  { tab: 'Social', icon: 'person.2.fill' },
  { tab: 'Calls', icon: 'phone.fill' },
  { tab: 'Nearby', icon: 'dot.radiowaves.left.and.right' },
  { tab: 'Chats', icon: 'message.fill' },
  { tab: 'SOS', icon: 'sos.circle.fill' },
];

const AVATAR_TEMPLATES = [
  { id: 'avatar-1', source: require('./assets/avatar-templates/avatar-1.jpg') },
  { id: 'avatar-2', source: require('./assets/avatar-templates/avatar-2.jpg') },
  { id: 'avatar-3', source: require('./assets/avatar-templates/avatar-3.jpg') },
  { id: 'avatar-4', source: require('./assets/avatar-templates/avatar-4.jpg') },
  { id: 'avatar-5', source: require('./assets/avatar-templates/avatar-5.jpg') },
  { id: 'avatar-6', source: require('./assets/avatar-templates/avatar-6.jpg') },
] as const;

const EMPTY_PROFILE: LocalProfile = { displayName: '', about: '', updatedAt: 0 };

const NEARBY_WORLD_SIZE = 1200;
const NEARBY_CENTER = NEARBY_WORLD_SIZE / 2;

function clampNumber(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function peerWorldPosition(peer: Peer) {
  let hash = 2166136261;
  for (let index = 0; index < peer.peerId.length; index += 1) {
    hash ^= peer.peerId.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  const angle = ((hash >>> 0) % 360) * (Math.PI / 180);
  const strength = clampNumber((peer.rssi + 100) / 58, 0.08, 1);
  const radius = 105 + (1 - strength) * 355;
  return {
    x: NEARBY_CENTER + Math.cos(angle) * radius,
    y: NEARBY_CENTER + Math.sin(angle) * radius,
  };
}

function touchDistance(touches: readonly { pageX: number; pageY: number }[]) {
  if (touches.length < 2) return 0;
  return Math.hypot(touches[0].pageX - touches[1].pageX, touches[0].pageY - touches[1].pageY);
}

export default function App() {
  const systemScheme = useColorScheme();
  const [tab, setTab] = useState<Tab>('Chats');
  const [settingsPage, setSettingsPage] = useState<'main' | 'network'>('main');
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
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [reliabilityDiagnostics, setReliabilityDiagnostics] = useState<ReliabilityDiagnostics | null>(null);
  const [reliabilityRun, setReliabilityRun] = useState<ReliabilityRun | null>(null);
  const [reliabilityNote, setReliabilityNote] = useState('Ready for M1.2D endurance tests.');
  const [selectedNearbyPeer, setSelectedNearbyPeer] = useState<Peer | null>(null);
  const [nearbyMode, setNearbyMode] = useState<'list' | 'field'>('list');
  const [nearbyTransform, setNearbyTransform] = useState({ x: 0, y: 0, scale: 1 });
  const [testTransport, setTestTransport] = useState<TestTransport>('wifi');
  const [mediaTransfers, setMediaTransfers] = useState<Record<string, MediaTransferView>>({});
  const [profile, setProfile] = useState<LocalProfile>(EMPTY_PROFILE);
  const [profileDraft, setProfileDraft] = useState<LocalProfile>(EMPTY_PROFILE);
  const [profileEditOpen, setProfileEditOpen] = useState(false);
  const [profileSaving, setProfileSaving] = useState(false);
  const [appearanceMode, setAppearanceMode] = useState<AppearanceMode>('dark');
  const [attachmentMenuOpen, setAttachmentMenuOpen] = useState(false);
  const resolvedAppearance = appearanceMode === 'system' ? (systemScheme === 'light' ? 'light' : 'dark') : appearanceMode;
  C = resolvedAppearance === 'light' ? LIGHT_COLORS : DARK_COLORS;
  styles = createStyles(C);
  const capabilities = useMemo(() => Telemetry.getCapabilities(), []);

  const runningRef = useRef(false);
  const peersRef = useRef<Record<string, Peer>>({});
  const contactsRef = useRef<Contact[]>([]);
  const trustedPeerRef = useRef<TrustedPeer | null>(null);
  const chatOpenRef = useRef(false);
  const pendingRef = useRef<Record<string, PendingIntent>>({});
  const retryTimersRef = useRef<Record<string, ReturnType<typeof setTimeout>>>({});
  const connectingRef = useRef<Set<string>>(new Set());
  const reliabilityBatchRef = useRef<Set<string>>(new Set());
  const reliabilityAutoStartedRef = useRef(false);
  const chatListRef = useRef<FlatList<ChatMessage>>(null);
  const nearbyTransformRef = useRef({ x: 0, y: 0, scale: 1 });
  const nearbyGestureStartRef = useRef({ x: 0, y: 0, scale: 1, pinchDistance: 0 });
  const nearbyResponder = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => false,
    onMoveShouldSetPanResponder: (event, gesture) =>
      event.nativeEvent.touches.length >= 2 || Math.abs(gesture.dx) > 4 || Math.abs(gesture.dy) > 4,
    onPanResponderGrant: event => {
      const current = nearbyTransformRef.current;
      const touches = event.nativeEvent.touches as unknown as { pageX: number; pageY: number }[];
      nearbyGestureStartRef.current = {
        ...current,
        pinchDistance: touchDistance(touches),
      };
    },
    onPanResponderMove: (event, gesture) => {
      const start = nearbyGestureStartRef.current;
      const touches = event.nativeEvent.touches as unknown as { pageX: number; pageY: number }[];
      let next;
      if (touches.length >= 2) {
        const distance = touchDistance(touches);
        const baseline = start.pinchDistance || distance || 1;
        next = { ...nearbyTransformRef.current, scale: clampNumber(start.scale * (distance / baseline), 0.62, 2.6) };
      } else {
        next = { x: start.x + gesture.dx, y: start.y + gesture.dy, scale: start.scale };
      }
      nearbyTransformRef.current = next;
      setNearbyTransform(next);
    },
    onPanResponderTerminationRequest: () => false,
  }), []);
  const backSwipe = useMemo(() => PanResponder.create({
    onMoveShouldSetPanResponder: (event, gesture) =>
      gesture.x0 <= 38 &&
      gesture.dx > 10 &&
      Math.abs(gesture.dy) < 28 &&
      Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.2,
    onMoveShouldSetPanResponderCapture: (event, gesture) =>
      gesture.x0 <= 38 &&
      gesture.dx > 10 &&
      Math.abs(gesture.dy) < 28 &&
      Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.2,
    onPanResponderRelease: (_event, gesture) => {
      if (gesture.dx > 72 || gesture.vx > 0.5) setChatOpen(false);
    },
    onPanResponderTerminationRequest: () => false,
  }), []);

  useEffect(() => { runningRef.current = running; }, [running]);
  useEffect(() => { peersRef.current = peers; }, [peers]);
  useEffect(() => { contactsRef.current = contacts; }, [contacts]);
  useEffect(() => { trustedPeerRef.current = trustedPeer; }, [trustedPeer]);
  useEffect(() => { chatOpenRef.current = chatOpen; }, [chatOpen]);
  useEffect(() => { nearbyTransformRef.current = nearbyTransform; }, [nearbyTransform]);

  function openConversationByDeviceId(deviceId: string) {
    const contact = contactsRef.current.find(item => item.deviceId === deviceId);
    if (!contact) {
      hydrateLocalState();
      return;
    }
    const peer = { peerId: contact.peerId, deviceId: contact.deviceId };
    trustedPeerRef.current = peer;
    setTrustedPeer(peer);
    setTab('Chats');
    setChatOpen(true);
    void Telemetry.markConversationRead(contact.deviceId).then(() => hydrateLocalState());
  }

  function refreshReliabilityDiagnostics() {
    try {
      setReliabilityDiagnostics(JSON.parse(Telemetry.getReliabilityDiagnostics()) as ReliabilityDiagnostics);
    } catch {
      setReliabilityDiagnostics(null);
    }
  }

  function hydrateLocalState() {
    try {
      const state = JSON.parse(Telemetry.getLocalState()) as LocalState;
      const localContacts = state.contacts ?? [];
      setProfile(state.profile ?? EMPTY_PROFILE);
      setAppearanceMode(state.appearance ?? 'dark');
      const existingPending = pendingRef.current;
      const contactByDevice = new Map(localContacts.map(contact => [contact.deviceId, contact]));
      const restoredPending: Record<string, PendingIntent> = {};

      for (const item of state.messages ?? []) {
        if (!item.mine || item.delivered) continue;
        const contact = contactByDevice.get(item.peerDeviceId);
        if (!contact) continue;
        restoredPending[item.id] = {
          localId: item.id,
          peerId: contact.peerId,
          text: item.text,
          transportId: existingPending[item.id]?.transportId,
          attemptCount: item.attemptCount,
          nextAttemptAt: item.nextAttemptAt,
          testTransport: existingPending[item.id]?.testTransport,
        };
      }

      pendingRef.current = restoredPending;
      contactsRef.current = localContacts;
      setContacts(localContacts);
      setMessages((state.messages ?? []).map(item => ({
        id: item.id,
        text: item.text,
        mine: item.mine,
        peerDeviceId: item.peerDeviceId,
        timestamp: item.timestamp,
        state: item.delivered ? 'delivered' : 'queued',
        transportId: item.mine ? item.id : undefined,
      })));
    } catch {
      setContacts([]);
    }
  }

  useEffect(() => {
    setIdentity(Telemetry.getIdentity());
    hydrateLocalState();
    refreshReliabilityDiagnostics();
    const launchArguments = Telemetry.getLaunchArguments();
    const payloadProbeLaunch = launchArguments.some(item => item.startsWith('--telemetry-payload-probe='));
    const countArgument = launchArguments.find(item => item.startsWith('--telemetry-reliability-count='));
    const transportArgument = launchArguments.find(item => item.startsWith('--telemetry-test-transport='));
    const rawTransport = transportArgument?.split('=')[1]?.toLowerCase();
    const requestedTransport: TestTransport = rawTransport === 'ble' || rawTransport === 'wifi' ? rawTransport : 'auto';
    setTestTransport(requestedTransport === 'auto' ? 'wifi' : requestedTransport);
    const requestedCount = countArgument ? Number(countArgument.split('=')[1]) : (launchArguments.includes('--telemetry-reliability-100') ? 100 : 0);
    if (requestedCount > 0 && requestedCount <= 200 && !reliabilityAutoStartedRef.current) {
      reliabilityAutoStartedRef.current = true;
      setTimeout(() => void runReliabilityEndurance(requestedCount, requestedTransport), 1400);
    }

    const subscriptions = [
      Telemetry.addListener('onState', event => {
        setNetworkState(event.detail ? `${event.state} · ${event.detail}` : event.state);
        const active = !['stopped', 'ready'].includes(event.state);
        setRunning(active);
        runningRef.current = active;

        if (event.state === 'disconnected' || event.state === 'waiting-peer' || event.state === 'transport-interrupted') {
          setSessionReady(false);
          if (event.detail) {
            connectingRef.current.delete(event.detail);
            for (const [id, intent] of Object.entries(pendingRef.current)) {
              if (intent.peerId === event.detail) pendingRef.current[id] = { ...intent, transportId: undefined };
            }
          }
          markPeerPending(event.detail, 'finding');
        }

        if (event.state === 'bluetooth-off') {
          setSessionReady(false);
          connectingRef.current.clear();
          for (const [id, intent] of Object.entries(pendingRef.current)) {
            pendingRef.current[id] = { ...intent, transportId: undefined };
          }
          setMessages(current => current.map(item =>
            item.mine && item.state !== 'delivered' ? { ...item, state: 'finding' } : item
          ));
        }

        if (event.state === 'bluetooth-on') void recoverPendingTransports();
      }),
      Telemetry.addListener('onPeerSeen', event => {
        const next = { ...peersRef.current, [event.peerId]: { ...event, lastSeen: Date.now() } };
        peersRef.current = next;
        setPeers(next);
        if (hasPendingForPeer(event.peerId) || payloadProbeLaunch) void ensureConnection(event.peerId);
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
        hydrateLocalState();
        setChatOpen(true);
        void flushPending(event.peerId);
      }),
      Telemetry.addListener('onMessage', event => {
        const peer = { peerId: event.peerId, deviceId: event.deviceId };
        trustedPeerRef.current = peer;
        setTrustedPeer(peer);
        setSessionReady(true);
        setMessages(current => current.some(item => item.id === event.messageId) ? current : [...current, {
          id: event.messageId,
          text: event.text,
          mine: false,
          peerDeviceId: event.deviceId,
          timestamp: Date.now(),
          state: 'delivered',
        }]);
        if (AppState.currentState === 'active' && chatOpenRef.current && trustedPeerRef.current?.deviceId === event.deviceId) {
          void Telemetry.markConversationRead(event.deviceId).then(() => hydrateLocalState());
        } else {
          hydrateLocalState();
        }
        refreshReliabilityDiagnostics();
      }),
      Telemetry.addListener('onMedia', event => {
        setMediaTransfers(current => ({
          ...current,
          [event.assetId]: {
            ...current[event.assetId],
            ...event,
            localUri: event.localUri ?? current[event.assetId]?.localUri,
            updatedAt: Date.now(),
          },
        }));
      }),
      Telemetry.addListener('onNotificationOpen', event => {
        Telemetry.consumePendingNotificationOpen();
        openConversationByDeviceId(event.deviceId);
      }),
      Telemetry.addListener('onDelivery', event => {
        let localId: string | undefined;
        for (const intent of Object.values(pendingRef.current)) {
          if (intent.transportId === event.messageId) {
            localId = intent.localId;
            break;
          }
        }
        if (localId) {
          clearRetryTimer(localId);
          delete pendingRef.current[localId];
          if (reliabilityBatchRef.current.delete(localId)) {
            setReliabilityRun(current => {
              if (!current) return current;
              const delivered = current.delivered + 1;
              if (delivered >= current.total) {
                console.log(`[TelemetryReliability] ENDURANCE_COMPLETE batch=${current.batchId} delivered=${delivered}/${current.total}`);
                setReliabilityNote(`PASS candidate · ${delivered}/${current.total} signed receipts received.`);
                if (Telemetry.getLaunchArguments().includes('--telemetry-reliability-replay-after')) {
                  setTimeout(() => void runReplayProbe(), 450);
                }
              }
              return { ...current, delivered, status: delivered >= current.total ? 'complete' : 'running' };
            });
          }
        }
        refreshReliabilityDiagnostics();
        setMessages(current => current.map(item =>
          item.transportId === event.messageId ? { ...item, state: 'delivered' } : item
        ));
        hydrateLocalState();
        void flushPending(event.peerId);
      }),
      Telemetry.addListener('onError', event => {
        if (isRecoverableTransportError(event.message)) return;
        Alert.alert('Telemetry', friendlyErrorMessage(event.message));
      }),
    ];

    if (launchArguments.includes('--telemetry-radio-paused')) {
      runningRef.current = false;
      setRunning(false);
      setNetworkState('Radio paused · reliability test mode');
    } else {
      void Telemetry.startOffline()
        .then(() => recoverPendingTransports())
        .catch(() => {
          setNetworkState('Radio paused');
        });
    }

    const appStateSubscription = AppState.addEventListener('change', state => {
      if (state !== 'active') return;
      for (const [id, intent] of Object.entries(pendingRef.current)) {
        pendingRef.current[id] = { ...intent, transportId: undefined, nextAttemptAt: undefined };
        clearRetryTimer(id);
      }
      hydrateLocalState();
      void recoverPendingTransports();
    });
    const recoveryPulse = setInterval(() => {
      if (AppState.currentState === 'active') void recoverPendingTransports();
    }, 3000);

    return () => {
      subscriptions.forEach(subscription => subscription.remove());
      appStateSubscription.remove();
      clearInterval(recoveryPulse);
      Object.values(retryTimersRef.current).forEach(timer => clearTimeout(timer));
      retryTimersRef.current = {};
    };
  }, []);

  const peerList = Object.values(peers).sort((a, b) => b.rssi - a.rssi);
  const selectedNearbyContact = selectedNearbyPeer
    ? contacts.find(item => item.peerId === selectedNearbyPeer.peerId)
    : undefined;
  const selectedNearbyName = selectedNearbyPeer
    ? selectedNearbyContact?.alias || selectedNearbyPeer.name || shortId(selectedNearbyPeer.peerId)
    : '';

  function setNearbyView(next: { x: number; y: number; scale: number }) {
    nearbyTransformRef.current = next;
    setNearbyTransform(next);
  }

  function centerNearby() {
    setNearbyView({ x: 0, y: 0, scale: 1 });
  }

  function zoomNearby(delta: number) {
    const current = nearbyTransformRef.current;
    setNearbyView({ ...current, scale: clampNumber(current.scale + delta, 0.62, 2.6) });
  }

  const activeMediaTransfers = Object.values(mediaTransfers)
    .filter(item => !trustedPeer?.deviceId || !item.peerDeviceId || item.peerDeviceId === trustedPeer.deviceId)
    .sort((a, b) => a.updatedAt - b.updatedAt);

  const routeLabel = sessionReady
    ? 'CONNECTED · ENCRYPTED'
    : networkState.startsWith('connecting') || networkState.startsWith('reconnecting')
      ? 'CONNECTING SECURELY…'
      : 'READY · AUTO DELIVERY';

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

  function clearRetryTimer(localId: string) {
    const timer = retryTimersRef.current[localId];
    if (!timer) return;
    clearTimeout(timer);
    delete retryTimersRef.current[localId];
  }

  function syncPendingMetadata(localId: string): boolean {
    const current = pendingRef.current[localId];
    if (!current) return false;
    try {
      const state = JSON.parse(Telemetry.getLocalState()) as LocalState;
      const stored = (state.messages ?? []).find(item => item.id === localId && item.mine);
      if (!stored) return true;
      if (stored.delivered) {
        clearRetryTimer(localId);
        delete pendingRef.current[localId];
        setMessageState(localId, 'delivered', localId);
        return false;
      }
      pendingRef.current[localId] = {
        ...current,
        attemptCount: stored.attemptCount,
        nextAttemptAt: stored.nextAttemptAt,
      };
      return true;
    } catch {
      // Keep the in-memory queue; the encrypted vault remains authoritative.
      return true;
    }
  }

  function scheduleRetry(localId: string) {
    const intent = pendingRef.current[localId];
    if (!intent) return;
    clearRetryTimer(localId);
    const target = intent.nextAttemptAt ?? Date.now() + 1000;
    const waitMs = Math.max(500, target - Date.now());
    retryTimersRef.current[localId] = setTimeout(() => {
      delete retryTimersRef.current[localId];
      if (!syncPendingMetadata(localId)) return;
      const current = pendingRef.current[localId];
      if (!current) return;
      pendingRef.current[localId] = { ...current, transportId: undefined, nextAttemptAt: undefined };
      setMessageState(localId, 'retry');
      void attemptDelivery(localId);
    }, waitMs);
  }

  async function ensureRadio() {
    if (runningRef.current) return;
    await Telemetry.startOffline();
    runningRef.current = true;
    setRunning(true);
  }
  async function recoverPendingTransports() {
    if (Telemetry.getLaunchArguments().includes('--telemetry-radio-paused')) return;
    const peerIds = [...new Set(Object.values(pendingRef.current)
      .filter(intent => !intent.transportId)
      .map(intent => intent.peerId))];
    for (const peerId of peerIds) {
      try {
        await ensureRadio();
        await Telemetry.recoverTransport(peerId);
      } catch {
        // Recovery is opportunistic; the encrypted queue remains authoritative.
      }
    }
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
    const otherInFlight = Object.values(pendingRef.current).some(item =>
      item.peerId === intent.peerId && item.localId !== localId && Boolean(item.transportId)
    );
    if (otherInFlight) return;
    if (intent.nextAttemptAt && intent.nextAttemptAt > Date.now()) {
      scheduleRetry(localId);
      return;
    }

    try {
      await ensureRadio();
      setMessageState(localId, 'sending');
      const transportId = intent.testTransport && intent.testTransport !== 'auto'
        ? await Telemetry.sendQueuedTextUsingTransport(intent.peerId, intent.localId, intent.testTransport)
        : await Telemetry.sendQueuedText(intent.peerId, intent.localId);
      pendingRef.current[localId] = { ...intent, transportId };
      setMessageState(localId, 'sending', transportId);
      syncPendingMetadata(localId);
      scheduleRetry(localId);
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
    const peerPending = Object.values(pendingRef.current).filter(item => item.peerId === peerId);
    if (peerPending.some(item => item.transportId)) return;
    const intent = peerPending.find(item => !item.transportId);
    if (intent) await attemptDelivery(intent.localId);
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

  async function pickAndSendMedia(kind: 'photo' | 'video' | 'file') {
    const peer = trustedPeerRef.current;
    if (!peer) {
      Alert.alert('Telemetry', 'Open a trusted conversation before attaching media.');
      return;
    }
    try {
      setAttachmentMenuOpen(false);
      let uri = '';
      let mimeType = 'application/octet-stream';
      let fileName = `telemetry-${Date.now()}`;

      if (kind === 'photo' || kind === 'video') {
        const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
        if (!permission.granted) {
          Alert.alert('Photos permission', 'Allow photo library access in Settings to attach photos or videos.');
          return;
        }
        const result = await ImagePicker.launchImageLibraryAsync({
          mediaTypes: kind === 'photo' ? ['images'] : ['videos'],
          quality: 1,
          allowsEditing: false,
        });
        if (result.canceled || !result.assets?.[0]) return;
        const asset = result.assets[0];
        uri = asset.uri;
        mimeType = asset.mimeType || (kind === 'photo' ? 'image/jpeg' : 'video/mp4');
        fileName = asset.fileName || `${kind}-${Date.now()}${kind === 'photo' ? '.jpg' : '.mp4'}`;
      } else {
        const result = await DocumentPicker.getDocumentAsync({
          copyToCacheDirectory: true,
          multiple: false,
          type: '*/*',
        });
        if (result.canceled || !result.assets?.[0]) return;
        const asset = result.assets[0];
        uri = asset.uri;
        mimeType = asset.mimeType || 'application/octet-stream';
        fileName = asset.name || `file-${Date.now()}`;
      }

      const assetId = await Telemetry.sendMedia(peer.deviceId, uri, kind, mimeType, fileName);
      setMediaTransfers(current => current[assetId] ? {
        ...current,
        [assetId]: { ...current[assetId], localUri: current[assetId].localUri || uri, updatedAt: Date.now() },
      } : current);
      void ensureRadio()
        .then(() => Telemetry.resumeMedia(peer.deviceId))
        .catch(() => undefined);
    } catch (error) {
      const message = String(error);
      if (/trusted peer key material/i.test(message)) {
        Alert.alert('Media transfer', 'This peer must be trusted again before encrypted media can be queued.');
      } else {
        Alert.alert('Media transfer', message);
      }
    }
  }

  function openAttachmentMenu() {
    setAttachmentMenuOpen(true);
  }

  async function changeAppearance(mode: AppearanceMode) {
    setAppearanceMode(mode);
    try {
      await Telemetry.setAppearance(mode);
    } catch {
      hydrateLocalState();
    }
  }

  async function sendText() {
    const text = draft.trim();
    const peer = trustedPeerRef.current;
    if (!text || !peer) return;

    try {
      const localId = await Telemetry.enqueueText(peer.peerId, peer.deviceId, text);
      pendingRef.current[localId] = { localId, peerId: peer.peerId, text };
      setMessages(current => [...current, {
        id: localId,
        text,
        mine: true,
        peerDeviceId: peer.deviceId,
        timestamp: Date.now(),
        state: 'queued',
      }]);
      setDraft('');
      void flushPending(peer.peerId);
    } catch (error) {
      Alert.alert('Telemetry', String(error));
    }
  }

  function openProfileEditor() {
    setProfileDraft(profile);
    setProfileEditOpen(true);
  }

  async function pickProfilePhoto() {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      allowsEditing: true,
      aspect: [1, 1],
      quality: 0.85,
    });
    if (result.canceled || !result.assets[0]) return;
    setProfileDraft(current => ({ ...current, photoUri: result.assets[0].uri, templateId: undefined }));
  }

  async function saveProfile() {
    if (profileSaving) return;
    setProfileSaving(true);
    try {
      const saved = await Telemetry.setLocalProfile(
        profileDraft.displayName,
        profileDraft.about,
        profileDraft.templateId ? null : profileDraft.photoUri ?? null,
        profileDraft.templateId ?? null,
      );
      setProfile(saved);
      setProfileDraft(saved);
      setProfileEditOpen(false);
      hydrateLocalState();
    } catch (error) {
      Alert.alert('Profile', `Could not save profile yet. ${String(error)}`);
    } finally {
      setProfileSaving(false);
    }
  }

  function reliabilityTarget(): TrustedPeer | null {
    if (trustedPeerRef.current) return trustedPeerRef.current;
    const contact = contactsRef.current[0];
    return contact ? { peerId: contact.peerId, deviceId: contact.deviceId } : null;
  }

  async function runReliabilityEndurance(total = 100, transport: TestTransport = testTransport) {
    const peer = reliabilityTarget();
    if (!peer) {
      Alert.alert('Reliability Lab', 'Trust at least one Telemetry device before running the endurance test.');
      return;
    }
    if (reliabilityRun?.status === 'queueing' || reliabilityRun?.status === 'running') return;

    const batchId = Date.now().toString(36).toUpperCase();
    const ids: string[] = [];
    setReliabilityRun({ batchId, total, delivered: 0, startedAt: Date.now(), status: 'queueing' });
    setReliabilityNote(`Queueing ${total} ${transport.toUpperCase()} test message${total === 1 ? '' : 's'}…`);
    Telemetry.resetReliabilityDiagnostics();
    refreshReliabilityDiagnostics();

    try {
      for (let index = 1; index <= total; index += 1) {
        const text = `[${transport.toUpperCase()} TEST ${batchId}] ${String(index).padStart(3, '0')}/${String(total).padStart(3, '0')}`;
        const localId = await Telemetry.enqueueText(peer.peerId, peer.deviceId, text);
        pendingRef.current[localId] = { localId, peerId: peer.peerId, text, testTransport: transport };
        ids.push(localId);
      }
      reliabilityBatchRef.current = new Set(ids);
      console.log(`[TelemetryReliability] ENDURANCE_START batch=${batchId} queued=${ids.length}`);
      setReliabilityRun(current => current ? { ...current, status: 'running' } : current);
      setReliabilityNote(`${total} ${transport.toUpperCase()} test message${total === 1 ? '' : 's'} queued. Waiting for signed receipts…`);
      hydrateLocalState();
      await ensureRadio();
      void recoverPendingTransports();
      void flushPending(peer.peerId);
    } catch (error) {
      setReliabilityRun(current => current ? { ...current, status: 'failed' } : current);
      setReliabilityNote(friendlyErrorMessage(String(error)));
    }
  }

  async function runReplayProbe() {
    const peer = reliabilityTarget();
    if (!peer) {
      Alert.alert('Reliability Lab', 'Open or trust a peer before running the replay probe.');
      return;
    }
    try {
      const sent = await Telemetry.replayLastEncryptedFrameForTest(peer.peerId);
      setReliabilityNote(sent ? 'Replay frame sent. Check receiver metric: duplicateFramesSuppressed should increase by 1.' : 'Replay frame was not sent.');
      setTimeout(refreshReliabilityDiagnostics, 1200);
    } catch (error) {
      setReliabilityNote(friendlyErrorMessage(String(error)));
    }
  }

  function resetReliabilityLab() {
    Telemetry.resetReliabilityDiagnostics();
    reliabilityBatchRef.current.clear();
    setReliabilityRun(null);
    setReliabilityNote('Reliability counters reset.');
    refreshReliabilityDiagnostics();
  }

  async function renameContact(contact: Contact) {
    Alert.prompt('Contact name', 'Set a local alias for this trusted device.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Save', onPress: (value?: string) => {
        const alias = (value ?? '').trim();
        void Telemetry.setContactAlias(contact.deviceId, alias).then(ok => {
          if (ok) hydrateLocalState();
        });
      } },
    ], 'plain-text', contact.alias || shortId(contact.deviceId));
  }

  function openContact(contact: Contact) {
    const peer = { peerId: contact.peerId, deviceId: contact.deviceId };
    trustedPeerRef.current = peer;
    setTrustedPeer(peer);
    setSessionReady(false);
    setChatOpen(true);
    void Telemetry.markConversationRead(contact.deviceId).then(() => hydrateLocalState());
  }

  const totalUnread = contacts.reduce((sum, contact) => sum + (contact.unreadCount ?? 0), 0);
  const activeMessages = trustedPeer
    ? messages.filter(item => item.peerDeviceId === trustedPeer.deviceId)
    : [];
  const activeContact = trustedPeer
    ? contacts.find(item => item.deviceId === trustedPeer.deviceId)
    : undefined;
  const activePending = activeMessages.filter(item => item.mine && item.state !== 'delivered');
  const activeHasPending = activePending.length > 0;
  const activePeerName = activeContact?.alias || (trustedPeer ? shortId(trustedPeer.deviceId) : 'Telemetry peer');
  const chatRouteLabel = sessionReady
    ? 'CONNECTED · ENCRYPTED'
    : activeHasPending
      ? 'WAITING FOR PEER'
      : routeLabel;

  useEffect(() => {
    if (!chatOpen) return;
    const timer = setTimeout(() => chatListRef.current?.scrollToEnd({ animated: false }), 80);
    return () => clearTimeout(timer);
  }, [chatOpen, trustedPeer?.deviceId, activeMessages.length]);

  return (
    <SafeAreaView style={styles.safe}>
      {tab !== 'Nearby' && (
        <View style={styles.header}>
          <View>
            <Text style={styles.brand}>Telemetry</Text>
            <Text style={styles.subtle}>Offline-first · adaptive transport</Text>
          </View>
          <Pressable style={styles.headerAvatarButton} onPress={() => { setSettingsPage('main'); setTab('Settings'); }} hitSlop={8}>
            <LocalProfileAvatar profile={profile} size={42} />
          </Pressable>
        </View>
      )}

      <View style={styles.content}>
        {tab === 'Social' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>Social</Text>
            <Text style={styles.copy}>Your trusted peer network, activity and shared updates will live here without exposing private message content.</Text>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Private social layer</Text>
              <Text style={styles.cardCopy}>Profile posts, reporting, comments and sharing are the next social milestone. Identity and trust stay peer-owned.</Text>
            </View>
          </ScrollView>
        )}

        {tab === 'Calls' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>Calls</Text>
            <Text style={styles.copy}>Voice and video sessions between trusted peers will appear here.</Text>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Voice · M1.6</Text>
              <Text style={styles.cardCopy}>Encrypted call signaling and direct Wi-Fi media are being wired for iOS. Video follows on the same authenticated session.</Text>
            </View>
          </ScrollView>
        )}

        {tab === 'Chats' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>Chats</Text>
            <Text style={styles.copy}>Send normally. Telemetry finds the route, reconnects and delivers in the background.</Text>
            <View style={styles.pillRow}>
              <Pill label="Encrypted" />
              <Pill label="Auto-route" />
              <Pill label="No cloud required" />
            </View>
            {contacts.length ? contacts.map(contact => {
              const last = [...messages].reverse().find(item => item.peerDeviceId === contact.deviceId);
              const isActive = trustedPeer?.deviceId === contact.deviceId;
              return (
                <Pressable key={contact.deviceId} style={[styles.card, styles.chatRow]} onPress={() => openContact(contact)} onLongPress={() => void renameContact(contact)}>
                  <View style={styles.rowBetween}>
                    <PeerAvatar name={contact.alias || shortId(contact.deviceId)} photoUri={contact.profilePhotoUri} trusted size={46} />
                    <View style={styles.flex}>
                      <Text style={styles.cardTitle}>{contact.alias || shortId(contact.deviceId)}</Text>
                      <Text style={styles.cardCopy}>{last?.text ?? 'Trusted peer · ready when reachable'}</Text>
                    </View>
                    <View style={styles.chatListMeta}>
                      {last?.timestamp ? <Text style={styles.listTime}>{formatConversationTime(last.timestamp)}</Text> : null}
                      {(contact.unreadCount ?? 0) > 0 ? (
                        <View style={styles.unreadBadge}><Text style={styles.unreadText}>{(contact.unreadCount ?? 0) > 99 ? '99+' : contact.unreadCount}</Text></View>
                      ) : <StatusDot active={!!isActive && sessionReady} />}
                    </View>
                  </View>
                  <Text style={styles.route}>{isActive ? routeLabel : 'TRUSTED · TAP TO OPEN · HOLD TO RENAME'}</Text>
                </Pressable>
              );
            }) : (
              <Empty title="No trusted conversations" copy="Open Nearby and verify a device once. After that, reconnects happen automatically when you send." />
            )}
          </ScrollView>
        )}

        {tab === 'Nearby' && (
          <View style={styles.nearbyScreen}>
            <View style={styles.nearbyHeader}>
              <View style={styles.flex}>
                <Text style={styles.nearbyTitle}>Nearby</Text>
                <Text style={styles.nearbySubtitle}>Live signal proximity · RSSI based · not GPS</Text>
                <Text style={styles.nearbyHeaderMeta}>{peerList.length} peer{peerList.length === 1 ? '' : 's'} detected · {running ? 'radio active' : 'radio paused'}</Text>
              </View>
              <Pressable style={styles.radioTextButton} onPress={toggleOffline}>
                <Text style={styles.radioTextButtonLabel}>{running ? 'Pause' : 'Resume'}</Text>
              </Pressable>
            </View>

            <View style={styles.nearbyModeSwitch}>
              <Pressable style={[styles.nearbyModeButton, nearbyMode === 'list' && styles.nearbyModeButtonActive]} onPress={() => setNearbyMode('list')}>
                <SymbolView name={'list.bullet' as any} size={15} tintColor={nearbyMode === 'list' ? '#FFF' : C.muted} />
                <Text style={[styles.nearbyModeText, nearbyMode === 'list' && styles.nearbyModeTextActive]}>List</Text>
              </Pressable>
              <Pressable style={[styles.nearbyModeButton, nearbyMode === 'field' && styles.nearbyModeButtonActive]} onPress={() => setNearbyMode('field')}>
                <SymbolView name={'map' as any} size={15} tintColor={nearbyMode === 'field' ? '#FFF' : C.muted} />
                <Text style={[styles.nearbyModeText, nearbyMode === 'field' && styles.nearbyModeTextActive]}>Field</Text>
              </Pressable>
            </View>

            <View style={styles.nearbyBody}>
              {nearbyMode === 'list' ? (
                <FlatList
                  data={peerList}
                  keyExtractor={item => item.peerId}
                  contentContainerStyle={styles.nearbyList}
                  renderItem={({ item }) => {
                    const contact = contacts.find(value => value.peerId === item.peerId);
                    const name = contact?.alias || item.name || shortId(item.peerId);
                    return (
                      <Pressable style={styles.nearbyListRow} onPress={() => setSelectedNearbyPeer(item)}>
                        <PeerAvatar name={name} photoUri={contact?.profilePhotoUri} trusted={!!contact} size={48} />
                        <View style={styles.flex}>
                          <View style={styles.rowBetween}>
                            <Text numberOfLines={1} style={styles.nearbyPeerName}>{name}</Text>
                            <Text style={styles.nearbyPeerRssi}>{item.rssi} dBm</Text>
                          </View>
                          <Text style={styles.nearbyPeerMeta}>{signalLabel(item.rssi)} signal · {proximityLabel(item.rssi)} · seen {lastSeenLabel(item.lastSeen)}</Text>
                          <Text style={styles.nearbyPeerState}>{contact ? 'Trusted contact' : 'New peer · verification required'}</Text>
                        </View>
                        <SymbolView name={'chevron.right' as any} size={14} tintColor={C.muted} />
                      </Pressable>
                    );
                  }}
                  ListEmptyComponent={<Empty title={running ? 'Scanning for nearby peers…' : 'Nearby radio is paused'} copy={running ? 'Detected Telemetry peers will appear here automatically.' : 'Resume the radio to discover nearby peers.'} />}
                />
              ) : (
                <>
                  <View style={styles.nearbyField} {...nearbyResponder.panHandlers}>
                    <View style={[styles.nearbyWorld, { transform: [{ translateX: nearbyTransform.x }, { translateY: nearbyTransform.y }, { scale: nearbyTransform.scale }] }] }>
                      {Array.from({ length: 13 }).map((_, index) => <View key={`v-${index}`} style={[styles.gridVertical, { left: index * 100 }]} />)}
                      {Array.from({ length: 13 }).map((_, index) => <View key={`h-${index}`} style={[styles.gridHorizontal, { top: index * 100 }]} />)}
                      {Array.from({ length: 169 }).map((_, index) => {
                        const x = index % 13;
                        const y = Math.floor(index / 13);
                        return <Text key={`plus-${x}-${y}`} pointerEvents="none" style={[styles.gridPlus, { left: x * 100 - 4, top: y * 100 - 7 }]}>+</Text>;
                      })}
                      <View style={styles.youNode}>
                        <PeerAvatar name="You" trusted size={46} />
                        <Text style={styles.youText}>YOU</Text>
                      </View>
                      {peerList.map(peer => {
                        const position = peerWorldPosition(peer);
                        const contact = contacts.find(item => item.peerId === peer.peerId);
                        const name = contact?.alias || peer.name || shortId(peer.peerId);
                        return (
                          <Pressable key={peer.peerId} onPress={() => setSelectedNearbyPeer(peer)} style={[styles.fieldPeer, { left: position.x - 56, top: position.y - 34 }] }>
                            <PeerAvatar name={name} photoUri={contact?.profilePhotoUri} trusted={!!contact} size={44} />
                            <Text numberOfLines={1} style={styles.fieldPeerLabel}>{name}</Text>
                            <Text style={styles.fieldPeerRssi}>{peer.rssi} dBm</Text>
                          </Pressable>
                        );
                      })}
                    </View>
                  </View>
                  <View style={styles.zoomRail}>
                    <Pressable style={styles.zoomButton} onPress={() => zoomNearby(0.22)}><Text style={styles.zoomText}>＋</Text></Pressable>
                    <Pressable style={styles.zoomButton} onPress={centerNearby}><SymbolView name={'scope' as any} size={18} tintColor={C.text} /></Pressable>
                    <Pressable style={styles.zoomButton} onPress={() => zoomNearby(-0.22)}><Text style={styles.zoomText}>−</Text></Pressable>
                  </View>
                  {!peerList.length && <View style={styles.nearbyEmpty} pointerEvents="none"><Text style={styles.nearbyEmptyTitle}>{running ? 'Scanning for nearby peers…' : 'Nearby radio is paused'}</Text><Text style={styles.nearbyEmptyCopy}>{running ? 'Peers will appear in this signal field automatically.' : 'Resume the radio to start discovery.'}</Text></View>}
                </>
              )}
            </View>

            {selectedNearbyPeer && (
              <View style={styles.peerProfileSheet}>
                <View style={styles.peerProfileHandle} />
                <View style={styles.peerProfileHeader}>
                  <PeerAvatar name={selectedNearbyName} photoUri={selectedNearbyContact?.profilePhotoUri} trusted={!!selectedNearbyContact} size={58} />
                  <View style={styles.flex}>
                    <Text style={styles.eyebrow}>{selectedNearbyContact ? 'TRUSTED CONTACT' : 'DETECTED PEER'}</Text>
                    <Text style={styles.peerProfileTitle}>{selectedNearbyName}</Text>
                    <Text style={styles.peerProfileMeta}>Seen {lastSeenLabel(selectedNearbyPeer.lastSeen)} · Bluetooth discovery</Text>
                  </View>
                  <Pressable style={styles.closeProfile} onPress={() => setSelectedNearbyPeer(null)}><Text style={styles.closeProfileText}>×</Text></Pressable>
                </View>
                <View style={styles.peerMetricsRow}>
                  <View style={styles.peerMetric}><Text style={styles.peerMetricValue}>{selectedNearbyPeer.rssi}</Text><Text style={styles.peerMetricLabel}>RSSI dBm</Text></View>
                  <View style={styles.peerMetric}><Text style={styles.peerMetricValue}>{signalLabel(selectedNearbyPeer.rssi)}</Text><Text style={styles.peerMetricLabel}>Signal</Text></View>
                  <View style={styles.peerMetric}><Text style={styles.peerMetricValue}>{proximityLabel(selectedNearbyPeer.rssi)}</Text><Text style={styles.peerMetricLabel}>Proximity</Text></View>
                </View>
                <Text style={styles.peerProfileId}>{selectedNearbyContact?.deviceId || selectedNearbyPeer.peerId}</Text>
                <Pressable style={styles.peerProfileAction} onPress={() => { const peer = selectedNearbyPeer; setSelectedNearbyPeer(null); void openNearbyPeer(peer); }}>
                  <Text style={styles.peerProfileActionText}>{selectedNearbyContact ? 'Open secure chat' : 'Verify identity'}</Text>
                </Pressable>
              </View>
            )}
          </View>
        )}

        {tab === 'Settings' && settingsPage === 'network' && (
          <ScrollView contentContainerStyle={styles.page}>
            <View style={styles.settingsSubHeader}>
              <Pressable style={styles.settingsBackButton} onPress={() => setSettingsPage('main')}>
                <SymbolView name={'chevron.left' as any} size={18} tintColor={C.blue} weight="semibold" />
                <Text style={styles.settingsBackText}>Settings</Text>
              </Pressable>
            </View>
            <Text style={styles.title}>Network</Text>
            <Text style={styles.copy}>Telemetry chooses the best available route. BLE is live today; longer-range transports plug into the same delivery engine.</Text>
            <TransportCard icon="dot.radiowaves.left.and.right" title="Bluetooth LE" detail="Discovery · bootstrap · direct chat" status={running ? 'ACTIVE' : 'READY'} active />
            <TransportCard icon="wifi" title="Wi‑Fi" detail="Automatic high-bandwidth transport upgrade" status={capabilities.wifiPeerToPeer ? 'ACTIVE' : 'NEXT'} />
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

        {tab === 'Settings' && settingsPage === 'main' && (
          <ScrollView contentContainerStyle={styles.page}>
            <Text style={styles.title}>Settings</Text>
            <Text style={styles.section}>Profile</Text>
            <View style={[styles.card, styles.profileCard]}>
              <LocalProfileAvatar profile={profile} size={76} />
              <View style={styles.profileCardBody}>
                <Text style={styles.profileName}>{profile.displayName || 'Set your Telemetry name'}</Text>
                <Text style={styles.profileAbout} numberOfLines={2}>{profile.about || 'Add a short status or description.'}</Text>
              </View>
              <Pressable style={styles.profilePencilButton} onPress={openProfileEditor} hitSlop={8}>
                <SymbolView name={'pencil' as any} size={18} tintColor={C.blue} weight="semibold" />
              </Pressable>
            </View>
            <Text style={styles.section}>Appearance</Text>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Theme</Text>
              <Text style={styles.cardCopy}>Choose how Telemetry looks on this iPhone.</Text>
              <View style={styles.appearanceSwitch}>
                {(['system', 'light', 'dark'] as AppearanceMode[]).map(mode => (
                  <Pressable key={mode} style={[styles.appearanceButton, appearanceMode === mode && styles.appearanceButtonActive]} onPress={() => void changeAppearance(mode)}>
                    <SymbolView name={(mode === 'system' ? 'circle.lefthalf.filled' : mode === 'light' ? 'sun.max.fill' : 'moon.fill') as any} size={15} tintColor={appearanceMode === mode ? '#FFF' : C.muted} />
                    <Text style={[styles.appearanceButtonText, appearanceMode === mode && styles.appearanceButtonTextActive]}>{mode[0].toUpperCase() + mode.slice(1)}</Text>
                  </Pressable>
                ))}
              </View>
            </View>
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
            <Text style={styles.section}>Network</Text>
            <Pressable style={[styles.card, styles.settingsMenuCard]} onPress={() => setSettingsPage('network')}>
              <View style={styles.settingsMenuIcon}><SymbolView name={'network' as any} size={21} tintColor={C.blue} /></View>
              <View style={styles.flex}>
                <Text style={styles.cardTitle}>Network & transport</Text>
                <Text style={styles.cardCopy}>BLE, Wi-Fi, mesh relay and gateway status.</Text>
              </View>
              <SymbolView name={'chevron.right' as any} size={15} tintColor={C.muted} />
            </Pressable>
            <Text style={styles.section}>Reliability Lab · M1.2D</Text>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Transport torture test</Text>
              <Text style={styles.cardCopy}>{reliabilityNote}</Text>
              {reliabilityRun ? (
                <Text style={styles.reliabilityProgress}>Batch {reliabilityRun.batchId} · {reliabilityRun.delivered}/{reliabilityRun.total} delivered · {reliabilityRun.status.toUpperCase()}</Text>
              ) : null}
              <Text style={styles.testTransportLabel}>Test transport</Text>
              <View style={styles.testTransportSwitch}>
                {(['ble', 'wifi', 'auto'] as TestTransport[]).map(mode => (
                  <Pressable key={mode} style={[styles.testTransportButton, testTransport === mode && styles.testTransportButtonActive]} onPress={() => setTestTransport(mode)}>
                    <Text style={[styles.testTransportText, testTransport === mode && styles.testTransportTextActive]}>{mode === 'wifi' ? 'WI-FI' : mode.toUpperCase()}</Text>
                  </Pressable>
                ))}
              </View>
              <View style={styles.reliabilityGrid}>
                <MetricCompact label="Pending" value={reliabilityDiagnostics?.pendingOutgoing ?? 0} />
                <MetricCompact label="Receipts" value={reliabilityDiagnostics?.deliveryReceiptsAccepted ?? 0} />
                <MetricCompact label="Accepted" value={reliabilityDiagnostics?.acceptedIncoming ?? 0} />
                <MetricCompact label="Dup suppressed" value={reliabilityDiagnostics?.duplicateFramesSuppressed ?? 0} />
                <MetricCompact label="Replay rejected" value={reliabilityDiagnostics?.replayRejected ?? 0} />
                <MetricCompact label="Duplicate IDs" value={reliabilityDiagnostics?.duplicateMessageIds ?? 0} />
              </View>
              <Pressable style={styles.labButton} onPress={() => void runReliabilityEndurance()}>
                <Text style={styles.labButtonText}>Run 100-message {testTransport === 'wifi' ? 'Wi-Fi' : testTransport.toUpperCase()} test</Text>
              </Pressable>
              <Pressable style={styles.labButtonSecondary} onPress={() => void runReplayProbe()}>
                <Text style={styles.labButtonSecondaryText}>Replay last encrypted frame</Text>
              </Pressable>
              <View style={styles.labFooter}>
                <Pressable onPress={refreshReliabilityDiagnostics}><Text style={styles.link}>Refresh metrics</Text></Pressable>
                <Pressable onPress={resetReliabilityLab}><Text style={styles.subtle}>Reset counters</Text></Pressable>
              </View>
            </View>
          </ScrollView>
        )}
      </View>

      <View style={styles.nav}>
        {NAV.map(item => {
          const active = tab === item.tab;
          return (
            <Pressable key={item.tab} style={styles.navItem} onPress={() => setTab(item.tab)}>
              <View style={styles.navIconWrap}>
                <SymbolView name={item.icon as any} size={23} tintColor={active ? C.cyan : C.muted} />
                {item.tab === 'Chats' && totalUnread > 0 ? (
                  <View style={styles.navBadge}><Text style={styles.navBadgeText}>{totalUnread > 99 ? '99+' : totalUnread}</Text></View>
                ) : null}
              </View>
              <Text style={[styles.navText, active && styles.navActive]}>{item.tab}</Text>
            </Pressable>
          );
        })}
      </View>

      <Modal visible={profileEditOpen} transparent animationType="slide" onRequestClose={() => setProfileEditOpen(false)}>
        <View style={styles.backdrop}>
          <View style={styles.profileSheet}>
            <View style={styles.rowBetween}>
              <View>
                <Text style={styles.eyebrow}>YOUR TELEMETRY PROFILE</Text>
                <Text style={styles.sheetTitle}>Edit profile</Text>
              </View>
              <Pressable style={styles.closeProfile} onPress={() => setProfileEditOpen(false)}><Text style={styles.closeProfileText}>×</Text></Pressable>
            </View>
            <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={styles.profileEditorScroll}>
              <View style={styles.profilePreview}>
                <LocalProfileAvatar profile={profileDraft} size={92} />
                <Pressable style={styles.uploadPhotoButton} onPress={() => void pickProfilePhoto()}>
                  <SymbolView name={'photo.fill' as any} size={17} tintColor="#FFF" />
                  <Text style={styles.uploadPhotoText}>Upload photo</Text>
                </Pressable>
              </View>
              <Text style={styles.fieldLabel}>Display name</Text>
              <TextInput
                value={profileDraft.displayName}
                onChangeText={displayName => setProfileDraft(current => ({ ...current, displayName }))}
                placeholder="Your name or alias"
                placeholderTextColor="#60758E"
                maxLength={48}
                style={styles.profileInput}
              />
              <Text style={styles.fieldLabel}>About</Text>
              <TextInput
                value={profileDraft.about}
                onChangeText={about => setProfileDraft(current => ({ ...current, about }))}
                placeholder="Short status or description"
                placeholderTextColor="#60758E"
                maxLength={120}
                multiline
                style={[styles.profileInput, styles.profileAboutInput]}
              />
              <Text style={styles.fieldLabel}>Avatar templates</Text>
              <View style={styles.avatarTemplateGrid}>
                {AVATAR_TEMPLATES.map(item => {
                  const selected = profileDraft.templateId === item.id;
                  return (
                    <Pressable
                      key={item.id}
                      style={[styles.avatarTemplateButton, selected && styles.avatarTemplateSelected]}
                      onPress={() => setProfileDraft(current => ({ ...current, templateId: item.id, photoUri: undefined }))}
                    >
                      <Image source={item.source} style={styles.avatarTemplateImage} />
                      {selected ? <View style={styles.avatarTemplateCheck}><Text style={styles.avatarTemplateCheckText}>✓</Text></View> : null}
                    </Pressable>
                  );
                })}
              </View>
              <Text style={styles.profilePrivacyNote}>Profile stays on this device for now. Cloud/profile discovery sync will be opt-in and will never expose private keys or message content.</Text>
              <Pressable style={[styles.primary, profileSaving && styles.buttonDisabled]} onPress={() => void saveProfile()} disabled={profileSaving}>
                <Text style={styles.primaryText}>{profileSaving ? 'Saving…' : 'Save profile'}</Text>
              </Pressable>
            </ScrollView>
          </View>
        </View>
      </Modal>

      <Modal visible={attachmentMenuOpen} transparent animationType="fade" onRequestClose={() => setAttachmentMenuOpen(false)}>
        <View style={styles.attachmentBackdrop}>
          <Pressable style={styles.attachmentDismissLayer} onPress={() => setAttachmentMenuOpen(false)} />
          <BlurView intensity={55} tint={resolvedAppearance === 'light' ? 'light' : 'dark'} style={styles.attachmentSheet}>
            <Image source={GLASS_NOISE} resizeMode="repeat" style={styles.glassNoiseStrong} />
            <Text style={styles.attachmentTitle}>Attach securely</Text>
            <Text style={styles.attachmentCopy}>Encrypted locally before transfer. Offline items stay queued.</Text>
            <View style={styles.attachmentActions}>
              {[
                ['photo', 'photo.fill', 'Photo'],
                ['video', 'video.fill', 'Video'],
                ['file', 'doc.fill', 'File'],
              ].map(([kind, icon, label]) => (
                <Pressable key={kind} style={styles.attachmentAction} onPress={() => void pickAndSendMedia(kind as 'photo' | 'video' | 'file')}>
                  <SymbolView name={icon as any} size={23} tintColor={C.blue} />
                  <Text style={styles.attachmentActionText}>{label}</Text>
                </Pressable>
              ))}
            </View>
          </BlurView>
        </View>
      </Modal>

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
        <SafeAreaView style={styles.safe} {...backSwipe.panHandlers}>
          <KeyboardAvoidingView style={styles.safe} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
            <View style={styles.chatHeader}>
              <Pressable style={styles.chatBackButton} onPress={() => setChatOpen(false)} hitSlop={10}>
                <SymbolView name={'chevron.left' as any} size={22} tintColor={C.blue} weight="semibold" />
              </Pressable>
              <Pressable style={styles.chatPeerHeader} onLongPress={() => activeContact && void renameContact(activeContact)}>
                <PeerAvatar name={activePeerName} photoUri={activeContact?.profilePhotoUri} trusted size={36} />
                <View>
                  <Text style={styles.chatPeerName}>{activePeerName}</Text>
                  <Text style={styles.chatPeerRoute}>{chatRouteLabel}</Text>
                </View>
              </Pressable>
              <View style={styles.headerSpacer} />
            </View>
            {activeHasPending && !sessionReady && (
              <View style={styles.offlineBanner}>
                <SymbolView name={'clock.arrow.circlepath' as any} size={18} tintColor={C.cyan} />
                <View style={styles.flex}>
                  <Text style={styles.offlineBannerTitle}>{activePeerName} is currently unavailable</Text>
                  <Text style={styles.offlineBannerCopy}>Messages are stored securely and will send automatically when the peer is reachable again.</Text>
                </View>
              </View>
            )}
            <FlatList
              ref={chatListRef}
              data={activeMessages}
              keyExtractor={item => item.id}
              contentContainerStyle={styles.messages}
              onLayout={() => chatListRef.current?.scrollToEnd({ animated: false })}
              onContentSizeChange={() => chatListRef.current?.scrollToEnd({ animated: false })}
              keyboardDismissMode="interactive"
              keyboardShouldPersistTaps="handled"
              maintainVisibleContentPosition={{ minIndexForVisible: 0 }}
              renderItem={({ item }) => (
                <View style={[styles.messageRow, item.mine ? styles.messageRowMine : styles.messageRowTheirs]}>
                  {!item.mine && <PeerAvatar name={activePeerName} photoUri={activeContact?.profilePhotoUri} trusted size={30} />}
                  <View style={styles.bubbleWrap}>
                    <View style={[styles.bubbleTail, item.mine ? styles.bubbleTailMine : styles.bubbleTailTheirs]} />
                    <BlurView intensity={28} tint={resolvedAppearance === 'light' ? 'light' : 'dark'} style={[styles.bubble, item.mine ? styles.mine : styles.theirs]}>
                      <Image source={GLASS_NOISE} resizeMode="repeat" style={styles.glassNoise} />
                      <Text style={styles.bubbleText}>{item.text}</Text>
                      <View style={styles.bubbleMeta}>
                        {item.mine && <Text style={styles.receipt}>{deliveryLabel(item.state)}</Text>}
                        <Text style={styles.timestamp}>{formatMessageTime(item.timestamp)}</Text>
                      </View>
                    </BlurView>
                  </View>
                </View>
              )}
              ListFooterComponent={activeMediaTransfers.length ? (
                <View style={styles.mediaTransferList}>
                  {activeMediaTransfers.map(item => <MediaTransferCard key={item.assetId} item={item} />)}
                </View>
              ) : null}
            />
            <View style={styles.composer}>
              <Pressable style={styles.attachButton} onPress={openAttachmentMenu}>
                <SymbolView name={'plus' as any} size={22} tintColor={C.blue} weight="bold" />
              </Pressable>
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

function MediaTransferCard({ item }: { item: MediaTransferView }) {
  const mine = item.state.startsWith('outgoing') || item.state === 'paused';
  const complete = item.state === 'outgoingComplete' || item.state === 'incomingReady';
  const done = item.acknowledgedChunks ?? item.receivedChunks ?? 0;
  const total = Math.max(1, item.totalChunks || 1);
  const percent = complete ? 100 : Math.min(99, Math.round((done / total) * 100));
  const label = item.state === 'paused' ? 'Paused · waiting for Wi-Fi'
    : item.state === 'incomingReady' ? 'Received · SHA-256 verified'
      : item.state === 'outgoingComplete' ? 'Sent securely'
        : `${percent}% · ${done}/${total} chunks`;
  return (
    <BlurView intensity={30} tint="dark" style={[styles.mediaBubble, mine ? styles.mediaMine : styles.mediaTheirs]}>
      <Image source={GLASS_NOISE} resizeMode="repeat" style={styles.glassNoise} />
      {item.kind === 'photo' && item.localUri ? <Image source={{ uri: item.localUri }} style={styles.mediaPreview} resizeMode="cover" /> : (
        <View style={styles.mediaFileIcon}>
          <SymbolView name={(item.kind === 'video' ? 'video.fill' : 'doc.fill') as any} size={28} tintColor={C.blue} />
        </View>
      )}
      <View style={styles.mediaInfo}>
        <Text style={styles.mediaFileName} numberOfLines={2}>{item.fileName}</Text>
        <Text style={styles.mediaMeta}>{formatBytes(item.byteLength)} · {item.kind.toUpperCase()}</Text>
        <View style={styles.mediaProgressTrack}><View style={[styles.mediaProgressFill, { width: `${percent}%` }]} /></View>
        <Text style={styles.mediaStatus}>{label}</Text>
      </View>
    </BlurView>
  );
}

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
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

function MetricCompact({ label, value }: { label: string; value: number }) {
  return <View style={styles.metricCompact}><Text style={styles.metricCompactValue}>{value}</Text><Text style={styles.metricCompactLabel}>{label}</Text></View>;
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

function LocalProfileAvatar({ profile, size = 72 }: { profile: LocalProfile; size?: number }) {
  const template = AVATAR_TEMPLATES.find(item => item.id === profile.templateId);
  const source = template?.source ?? (profile.photoUri ? { uri: profile.photoUri } : undefined);
  return (
    <View style={[styles.avatarShell, { width: size, height: size, borderRadius: size / 2 }, styles.avatarTrusted]}>
      {source ? (
        <Image source={source} style={{ width: size, height: size, borderRadius: size / 2 }} resizeMode="cover" />
      ) : (
        <SymbolView name={'person.crop.circle.fill' as any} size={Math.round(size * 0.72)} tintColor={C.blue} />
      )}
    </View>
  );
}

function PeerAvatar({ name, photoUri, trusted = false, size = 44 }: { name: string; photoUri?: string; trusted?: boolean; size?: number }) {
  return (
    <View style={[styles.avatarShell, { width: size, height: size, borderRadius: size / 2 }, trusted && styles.avatarTrusted]}>
      {photoUri ? (
        <Image source={{ uri: photoUri }} style={{ width: size, height: size, borderRadius: size / 2 }} />
      ) : (
        <SymbolView name={'person.crop.circle.fill' as any} size={Math.round(size * 0.72)} tintColor={trusted ? C.blue : '#7890AA'} />
      )}
    </View>
  );
}

function deliveryLabel(state?: DeliveryState) {
  switch (state) {
    case 'delivered': return 'Delivered · verified receipt';
    case 'sending': return 'Sending…';
    case 'connecting': return 'Connecting securely…';
    case 'finding': return 'Waiting for peer…';
    case 'retry': return 'Will retry automatically';
    default: return 'Stored · waiting for peer';
  }
}

function formatConversationTime(timestamp?: number) {
  if (!timestamp) return '';
  const date = new Date(timestamp);
  const now = new Date();
  if (date.toDateString() === now.toDateString()) return formatMessageTime(timestamp);
  return `${String(date.getDate()).padStart(2, '0')}/${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function formatMessageTime(timestamp?: number) {
  if (!timestamp) return '';
  const date = new Date(timestamp);
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  return `${hours}:${minutes}`;
}

function isRecoverableTransportError(value: string) {
  return /BLE connection|connection is not active|secure peer session|no longer available|characteristic is unavailable|transmit queue is busy|not connected|unknown ATT|ATT error|CBATT|GATT|attribute protocol/i.test(value);
}

function friendlyErrorMessage(value: string) {
  if (/Bluetooth permission|unauthorized/i.test(value)) {
    return 'Bluetooth permission is required. Enable Bluetooth access in Settings so Telemetry can discover nearby peers.';
  }
  if (/Bluetooth.*powered off|poweredOff/i.test(value)) {
    return 'Bluetooth is turned off on this iPhone. Turn it on to continue.';
  }
  return 'The connection cannot continue yet. Telemetry will keep stored messages safe and retry automatically.';
}

function shortId(value: string) { return value.replace('tlm:device:', 'TLM ').slice(0, 16); }
function signalLabel(rssi: number) { return rssi >= -60 ? 'Strong' : rssi >= -75 ? 'Fair' : 'Weak'; }
function proximityLabel(rssi: number) { return rssi >= -55 ? 'Very close' : rssi >= -65 ? 'Near' : rssi >= -78 ? 'Medium' : 'Edge'; }
function lastSeenLabel(timestamp: number) {
  const seconds = Math.max(0, Math.round((Date.now() - timestamp) / 1000));
  if (seconds < 3) return 'now';
  if (seconds < 60) return `${seconds}s ago`;
  return `${Math.floor(seconds / 60)}m ago`;
}

function createStyles(C: ThemeColors) {
  return StyleSheet.create({
  safe: { flex: 1, backgroundColor: C.ink },
  flex: { flex: 1 },
  center: { alignItems: 'center' },
  rowBetween: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  header: { height: 72, paddingHorizontal: 20, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: C.line },
  brand: { color: C.text, fontWeight: '800', fontSize: 24 },
  subtle: { color: C.muted, fontSize: 12, marginTop: 3 },
  headerAvatarButton: { width: 46, height: 46, borderRadius: 23, alignItems: 'center', justifyContent: 'center' },
  statusDot: { width: 9, height: 9, borderRadius: 99 },
  content: { flex: 1 },
  page: { flexGrow: 1, padding: 20, paddingBottom: 118 },
  title: { color: C.text, fontSize: 34, fontWeight: '800', marginBottom: 8 },
  copy: { color: C.muted, fontSize: 14, lineHeight: 20 },
  pillRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginVertical: 18 },
  pill: { paddingHorizontal: 12, paddingVertical: 7, borderRadius: 99, backgroundColor: C.card2, borderWidth: 1, borderColor: '#2F86FF66' },
  pillText: { color: C.cyan, fontSize: 11, fontWeight: '700' },
  card: { backgroundColor: C.card, borderRadius: 18, padding: 16, borderWidth: 1, borderColor: '#17304B' },
  profileCard: { flexDirection: 'row', alignItems: 'center', gap: 16 },
  profileCardBody: { flex: 1 },
  profileName: { color: C.text, fontSize: 19, fontWeight: '800' },
  profileAbout: { color: C.muted, fontSize: 12, lineHeight: 17, marginTop: 4 },
  profilePencilButton: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', backgroundColor: C.glass, borderWidth: 1, borderColor: C.line },
  settingsSubHeader: { minHeight: 38, justifyContent: 'center', marginBottom: 6 },
  settingsBackButton: { alignSelf: 'flex-start', minHeight: 36, flexDirection: 'row', alignItems: 'center', gap: 4, paddingRight: 12 },
  settingsBackText: { color: C.blue, fontSize: 14, fontWeight: '700' },
  settingsMenuCard: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  settingsMenuIcon: { width: 42, height: 42, borderRadius: 14, alignItems: 'center', justifyContent: 'center', backgroundColor: C.glass },
  appearanceSwitch: { flexDirection: 'row', gap: 8, marginTop: 14 },
  appearanceButton: { flex: 1, height: 42, borderRadius: 13, flexDirection: 'row', gap: 7, alignItems: 'center', justifyContent: 'center', backgroundColor: C.glass, borderWidth: 1, borderColor: C.line },
  appearanceButtonActive: { backgroundColor: C.blue, borderColor: C.blue },
  appearanceButtonText: { color: C.muted, fontSize: 11, fontWeight: '800' },
  appearanceButtonTextActive: { color: '#FFF' },
  chatRow: { marginBottom: 10 },
  chatListMeta: { alignItems: 'flex-end', gap: 8, minWidth: 44 },
  listTime: { color: C.muted, fontSize: 10 },
  unreadBadge: { minWidth: 22, height: 22, paddingHorizontal: 6, borderRadius: 11, backgroundColor: C.cyan, alignItems: 'center', justifyContent: 'center' },
  unreadText: { color: C.ink, fontSize: 10, fontWeight: '900' },
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
  nearbyScreen: { flex: 1, backgroundColor: C.ink },
  nearbyHeader: { paddingHorizontal: 18, paddingTop: 12, paddingBottom: 8, flexDirection: 'row', alignItems: 'flex-start', gap: 14 },
  nearbyTitle: { color: C.text, fontSize: 30, fontWeight: '800' },
  nearbySubtitle: { color: C.muted, fontSize: 11, marginTop: 3 },
  nearbyHeaderMeta: { color: '#6F86A0', fontSize: 10, marginTop: 5 },
  radioTextButton: { minWidth: 68, height: 36, borderRadius: 12, backgroundColor: C.card2, borderWidth: 1, borderColor: '#274765', alignItems: 'center', justifyContent: 'center', paddingHorizontal: 12 },
  radioTextButtonLabel: { color: C.blue, fontSize: 11, fontWeight: '800' },
  nearbyModeSwitch: { marginHorizontal: 18, marginBottom: 10, padding: 3, borderRadius: 13, backgroundColor: '#0A1725', borderWidth: 1, borderColor: '#1C3148', flexDirection: 'row' },
  nearbyModeButton: { flex: 1, height: 36, borderRadius: 10, flexDirection: 'row', gap: 7, alignItems: 'center', justifyContent: 'center' },
  nearbyModeButtonActive: { backgroundColor: C.blue },
  nearbyModeText: { color: C.muted, fontSize: 12, fontWeight: '700' },
  nearbyModeTextActive: { color: '#FFF' },
  nearbyBody: { flex: 1, overflow: 'hidden', borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.line },
  nearbyList: { padding: 14, paddingBottom: 28 },
  nearbyListRow: { minHeight: 76, padding: 13, marginBottom: 9, borderRadius: 16, backgroundColor: C.card, borderWidth: 1, borderColor: '#18314B', flexDirection: 'row', alignItems: 'center', gap: 12 },
  nearbyPeerName: { color: C.text, fontSize: 15, fontWeight: '800', flexShrink: 1 },
  nearbyPeerRssi: { color: C.blue, fontSize: 10, fontWeight: '800' },
  nearbyPeerMeta: { color: C.muted, fontSize: 10, marginTop: 4 },
  nearbyPeerState: { color: '#6F86A0', fontSize: 9, marginTop: 4 },
  nearbyField: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, backgroundColor: C.field },
  nearbyWorld: { position: 'absolute', width: NEARBY_WORLD_SIZE, height: NEARBY_WORLD_SIZE, left: '50%', top: '50%', marginLeft: -NEARBY_CENTER, marginTop: -NEARBY_CENTER },
  gridVertical: { position: 'absolute', top: 0, bottom: 0, width: 1, backgroundColor: resolvedGridColor(C) },
  gridHorizontal: { position: 'absolute', left: 0, right: 0, height: 1, backgroundColor: resolvedGridColor(C) },
  gridPlus: { position: 'absolute', color: C.muted, opacity: 0.38, fontSize: 11, fontWeight: '500', width: 10, height: 14, textAlign: 'center', zIndex: 1 },
  youNode: { position: 'absolute', left: NEARBY_CENTER - 54, top: NEARBY_CENTER - 38, width: 108, alignItems: 'center', justifyContent: 'center' },
  youText: { color: C.blue, fontSize: 8, fontWeight: '800', marginTop: 4, letterSpacing: 0.5 },
  fieldPeer: { position: 'absolute', width: 112, minHeight: 70, alignItems: 'center', justifyContent: 'flex-start', paddingTop: 2, zIndex: 4 },
  fieldPeerLabel: { color: C.text, fontSize: 9, fontWeight: '700', marginTop: 4, maxWidth: 108, textAlign: 'center' },
  fieldPeerRssi: { color: '#7890AA', fontSize: 8, marginTop: 2 },
  zoomRail: { position: 'absolute', right: 14, bottom: 18, gap: 7, zIndex: 20 },
  zoomButton: { width: 40, height: 40, borderRadius: 12, alignItems: 'center', justifyContent: 'center', backgroundColor: '#0D1B2CDD', borderWidth: 1, borderColor: '#2A4663' },
  zoomText: { color: C.text, fontSize: 21, fontWeight: '500', marginTop: -2 },
  nearbyEmpty: { position: 'absolute', left: 36, right: 36, top: '40%', alignItems: 'center', padding: 18, borderRadius: 16, backgroundColor: '#0D1B2CEB', borderWidth: 1, borderColor: '#223A53' },
  nearbyEmptyTitle: { color: C.text, fontSize: 14, fontWeight: '800' },
  nearbyEmptyCopy: { color: C.muted, fontSize: 10, textAlign: 'center', marginTop: 5 },
  peerProfileSheet: { position: 'absolute', left: 10, right: 10, bottom: 10, padding: 18, paddingTop: 10, borderRadius: 22, backgroundColor: '#0B1828FA', borderWidth: 1, borderColor: '#294665', zIndex: 40 },
  peerProfileHandle: { width: 38, height: 4, borderRadius: 2, backgroundColor: '#40576E', alignSelf: 'center', marginBottom: 12 },
  peerProfileHeader: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  peerProfileTitle: { color: C.text, fontSize: 20, fontWeight: '800', marginTop: 2 },
  closeProfile: { width: 34, height: 34, borderRadius: 17, backgroundColor: '#14283C', alignItems: 'center', justifyContent: 'center' },
  closeProfileText: { color: C.text, fontSize: 23, lineHeight: 26 },
  peerMetricsRow: { flexDirection: 'row', gap: 8, marginTop: 14 },
  peerMetric: { flex: 1, minHeight: 58, borderRadius: 12, backgroundColor: '#102337', alignItems: 'center', justifyContent: 'center', paddingHorizontal: 6 },
  peerMetricValue: { color: C.text, fontSize: 13, fontWeight: '800', textAlign: 'center' },
  peerMetricLabel: { color: C.muted, fontSize: 8, marginTop: 3, textAlign: 'center' },
  peerProfileId: { color: '#7390AD', fontFamily: 'Menlo', fontSize: 9, marginTop: 13 },
  peerProfileMeta: { color: C.muted, fontSize: 10, marginTop: 3 },
  peerProfileAction: { minHeight: 48, borderRadius: 14, marginTop: 14, backgroundColor: C.blue, alignItems: 'center', justifyContent: 'center' },
  peerProfileActionText: { color: '#FFF', fontWeight: '800', fontSize: 13 },
  avatarShell: { alignItems: 'center', justifyContent: 'center', backgroundColor: '#12243A', borderWidth: 1, borderColor: '#2A4058', overflow: 'hidden' },
  avatarTrusted: { borderColor: C.blue },
  testTransportLabel: { color: C.muted, fontSize: 10, fontWeight: '800', textTransform: 'uppercase', letterSpacing: 0.8, marginTop: 14, marginBottom: 7 },
  testTransportSwitch: { flexDirection: 'row', padding: 3, borderRadius: 12, backgroundColor: '#0A1725', borderWidth: 1, borderColor: '#1C3148' },
  testTransportButton: { flex: 1, minHeight: 34, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },
  testTransportButtonActive: { backgroundColor: C.blue },
  testTransportText: { color: C.muted, fontSize: 10, fontWeight: '800' },
  testTransportTextActive: { color: '#FFF' },
  mono: { color: '#67809E', fontSize: 10, marginTop: 5, fontFamily: 'Menlo' },
  link: { color: C.blue, fontWeight: '800', fontSize: 13 },
  empty: { marginTop: 16, backgroundColor: C.card, borderRadius: 18, padding: 18, borderWidth: 1, borderColor: '#17304B' },
  metric: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 15, borderBottomColor: '#1B3047', borderBottomWidth: StyleSheet.hairlineWidth },
  metricLabel: { color: C.muted },
  metricValue: { color: C.cyan, fontWeight: '800', fontSize: 12 },
  reliabilityProgress: { color: C.cyan, fontSize: 11, fontWeight: '800', marginTop: 12 },
  reliabilityGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 14 },
  metricCompact: { width: '31%', minHeight: 62, borderRadius: 12, backgroundColor: C.card2, alignItems: 'center', justifyContent: 'center', padding: 8 },
  metricCompactValue: { color: C.text, fontSize: 18, fontWeight: '900' },
  metricCompactLabel: { color: C.muted, fontSize: 9, marginTop: 3, textAlign: 'center' },
  labButton: { marginTop: 14, minHeight: 46, borderRadius: 14, backgroundColor: C.blue, alignItems: 'center', justifyContent: 'center' },
  labButtonText: { color: '#FFF', fontWeight: '800', fontSize: 13 },
  labButtonSecondary: { marginTop: 8, minHeight: 44, borderRadius: 14, backgroundColor: C.card2, borderWidth: 1, borderColor: '#28537E', alignItems: 'center', justifyContent: 'center' },
  labButtonSecondaryText: { color: C.text, fontWeight: '700', fontSize: 12 },
  labFooter: { marginTop: 13, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  section: { color: C.muted, fontWeight: '800', marginTop: 22, marginBottom: 8, textTransform: 'uppercase', fontSize: 11, letterSpacing: 1 },
  transportCard: { marginTop: 10, minHeight: 76, padding: 14, borderRadius: 18, backgroundColor: C.card, borderWidth: 1, borderColor: '#17304B', flexDirection: 'row', alignItems: 'center', gap: 12 },
  transportActive: { borderColor: '#2F86FF88' },
  transportIcon: { width: 42, height: 42, borderRadius: 13, alignItems: 'center', justifyContent: 'center', backgroundColor: '#102337' },
  transportStatus: { color: C.muted, fontSize: 10, fontWeight: '800' },
  transportStatusActive: { color: C.cyan },
  sosPanel: { marginTop: 22, padding: 22, alignItems: 'center', backgroundColor: C.card, borderRadius: 24, borderWidth: 1, borderColor: '#5B2430' },
  sosTitle: { color: C.text, fontSize: 22, fontWeight: '800', marginTop: 12 },
  sosButton: { marginTop: 24, minHeight: 58, alignSelf: 'stretch', backgroundColor: '#A91E31', borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  sosText: { color: '#FFF', fontWeight: '800', fontSize: 15 },
  nav: { position: 'absolute', left: 14, right: 14, bottom: 10, height: 72, paddingHorizontal: 5, paddingVertical: 5, flexDirection: 'row', backgroundColor: C.glassStrong, borderWidth: 1, borderColor: C.line, borderRadius: 25, shadowColor: '#000', shadowOpacity: 0.28, shadowRadius: 18, shadowOffset: { width: 0, height: 8 }, elevation: 14 },
  navItem: { flex: 1, minHeight: 58, borderRadius: 20, alignItems: 'center', justifyContent: 'center', gap: 3 },
  navIconWrap: { position: 'relative', minWidth: 30, alignItems: 'center' },
  navBadge: { position: 'absolute', top: -7, right: -9, minWidth: 18, height: 18, paddingHorizontal: 4, borderRadius: 9, backgroundColor: C.cyan, alignItems: 'center', justifyContent: 'center' },
  navBadgeText: { color: C.ink, fontSize: 9, fontWeight: '900' },
  navText: { color: C.muted, fontSize: 12, fontWeight: '600' },
  navActive: { color: C.cyan, fontWeight: '800' },
  backdrop: { flex: 1, backgroundColor: '#000A', justifyContent: 'flex-end' },
  sheet: { backgroundColor: C.card, padding: 24, paddingBottom: 38, borderTopLeftRadius: 28, borderTopRightRadius: 28 },
  profileSheet: { maxHeight: '92%', backgroundColor: C.card, padding: 20, paddingBottom: 26, borderTopLeftRadius: 28, borderTopRightRadius: 28 },
  profileEditorScroll: { paddingBottom: 18 },
  profilePreview: { alignItems: 'center', marginTop: 22, marginBottom: 8 },
  uploadPhotoButton: { marginTop: 12, minHeight: 38, borderRadius: 12, backgroundColor: '#183A5D', paddingHorizontal: 14, flexDirection: 'row', alignItems: 'center', gap: 8 },
  uploadPhotoText: { color: '#FFF', fontWeight: '800', fontSize: 12 },
  fieldLabel: { color: C.muted, fontWeight: '800', fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.8, marginTop: 16, marginBottom: 7 },
  profileInput: { minHeight: 48, borderRadius: 14, borderWidth: 1, borderColor: '#28445F', backgroundColor: '#0A1725', color: C.text, paddingHorizontal: 14, fontSize: 14 },
  profileAboutInput: { minHeight: 82, paddingTop: 13, textAlignVertical: 'top' },
  avatarTemplateGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  avatarTemplateButton: { width: '30%', aspectRatio: 1, borderRadius: 18, overflow: 'hidden', borderWidth: 2, borderColor: '#203A55', position: 'relative' },
  avatarTemplateSelected: { borderColor: C.blue },
  avatarTemplateImage: { width: '100%', height: '100%' },
  avatarTemplateCheck: { position: 'absolute', right: 6, top: 6, width: 22, height: 22, borderRadius: 11, backgroundColor: C.blue, alignItems: 'center', justifyContent: 'center' },
  avatarTemplateCheckText: { color: '#FFF', fontWeight: '900', fontSize: 12 },
  profilePrivacyNote: { color: '#6F86A0', fontSize: 10, lineHeight: 15, marginTop: 16 },
  buttonDisabled: { opacity: 0.55 },
  eyebrow: { color: C.cyan, letterSpacing: 1.5, fontSize: 10, fontWeight: '800' },
  sheetTitle: { color: C.text, fontSize: 25, fontWeight: '800', marginTop: 5 },
  code: { color: C.text, fontFamily: 'Menlo', fontSize: 42, fontWeight: '800', textAlign: 'center', marginVertical: 26, letterSpacing: 3 },
  secondary: { height: 48, alignItems: 'center', justifyContent: 'center', marginTop: 6 },
  chatHeader: { height: 68, paddingHorizontal: 14, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: C.line },
  chatBackButton: { width: 42, height: 42, borderRadius: 21, alignItems: 'center', justifyContent: 'center' },
  chatPeerHeader: { position: 'absolute', left: 58, right: 58, minHeight: 48, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 9 },
  chatPeerName: { color: C.text, fontSize: 15, fontWeight: '800' },
  chatPeerRoute: { color: C.cyan, fontSize: 8, fontWeight: '800', letterSpacing: 0.7, marginTop: 2 },
  headerSpacer: { width: 42 },
  offlineBanner: { marginHorizontal: 12, marginTop: 10, paddingHorizontal: 13, paddingVertical: 11, borderRadius: 15, flexDirection: 'row', alignItems: 'center', gap: 10, backgroundColor: '#102337', borderWidth: 1, borderColor: '#2F86FF88' },
  offlineBannerTitle: { color: C.text, fontSize: 12, fontWeight: '800' },
  offlineBannerCopy: { color: C.muted, fontSize: 11, lineHeight: 15, marginTop: 2 },
  messages: { padding: 16, paddingBottom: 22, gap: 10 },
  messageRow: { width: '100%', flexDirection: 'row', alignItems: 'flex-end' },
  messageRowMine: { justifyContent: 'flex-end' },
  messageRowTheirs: { justifyContent: 'flex-start', gap: 8 },
  bubbleWrap: { maxWidth: '82%', position: 'relative' },
  bubble: { paddingHorizontal: 14, paddingVertical: 11, borderRadius: 18, overflow: 'hidden', borderWidth: 1, borderColor: C.line, zIndex: 2 },
  bubbleTail: { position: 'absolute', bottom: 10, width: 14, height: 14, transform: [{ rotate: '45deg' }], borderWidth: 1, borderColor: C.line, zIndex: 1 },
  bubbleTailMine: { right: -4, backgroundColor: resolvedMineGlass(C) },
  bubbleTailTheirs: { left: -4, backgroundColor: C.glass },
  mine: { backgroundColor: resolvedMineGlass(C) },
  theirs: { backgroundColor: C.glass },
  bubbleText: { color: C.text, fontSize: 16, lineHeight: 21 },
  bubbleMeta: { marginTop: 6, flexDirection: 'row', alignItems: 'center', justifyContent: 'flex-end', gap: 8 },
  receipt: { color: '#BBD5F2', fontSize: 10, flexShrink: 1 },
  timestamp: { color: '#8FA9C5', fontSize: 10 },
  mediaTransferList: { gap: 10, paddingTop: 4 },
  mediaBubble: { maxWidth: '88%', minWidth: 230, borderRadius: 18, padding: 10, flexDirection: 'row', gap: 10, borderWidth: 1 },
  mediaMine: { alignSelf: 'flex-end', backgroundColor: '#102C4B', borderColor: '#2F86FF66' },
  mediaTheirs: { alignSelf: 'flex-start', backgroundColor: C.card2, borderColor: '#29435E' },
  mediaPreview: { width: 76, height: 76, borderRadius: 12, backgroundColor: '#091522' },
  mediaFileIcon: { width: 76, height: 76, borderRadius: 12, backgroundColor: '#0A1725', alignItems: 'center', justifyContent: 'center' },
  mediaInfo: { flex: 1, minWidth: 0, justifyContent: 'center' },
  mediaFileName: { color: C.text, fontSize: 13, fontWeight: '800' },
  mediaMeta: { color: C.muted, fontSize: 9, marginTop: 4 },
  mediaProgressTrack: { height: 4, borderRadius: 3, backgroundColor: '#203247', overflow: 'hidden', marginTop: 9 },
  mediaProgressFill: { height: 4, borderRadius: 3, backgroundColor: C.blue },
  mediaStatus: { color: C.cyan, fontSize: 9, fontWeight: '700', marginTop: 6 },
  attachButton: { width: 48, height: 48, borderRadius: 16, backgroundColor: C.glass, borderWidth: 1, borderColor: C.line, alignItems: 'center', justifyContent: 'center' },
  composer: { paddingHorizontal: 12, paddingTop: 10, paddingBottom: 10, flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: C.ink, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.line },
  input: { flex: 1, height: 48, backgroundColor: C.glass, color: C.text, borderRadius: 16, borderWidth: 1, borderColor: C.line, paddingHorizontal: 16, paddingTop: 12, paddingBottom: 12, fontSize: 16 },
  glassNoise: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, width: '100%', height: '100%', opacity: 0.12 },
  glassNoiseStrong: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, width: '100%', height: '100%', opacity: 0.17 },
  attachmentBackdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.42)', justifyContent: 'flex-end', padding: 14, paddingBottom: 28, position: 'relative' },
  attachmentDismissLayer: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 },
  attachmentSheet: { borderRadius: 26, padding: 18, overflow: 'hidden', borderWidth: 1, borderColor: C.line, backgroundColor: C.glassStrong },
  attachmentTitle: { color: C.text, fontSize: 18, fontWeight: '800' },
  attachmentCopy: { color: C.muted, fontSize: 11, lineHeight: 16, marginTop: 4 },
  attachmentActions: { flexDirection: 'row', gap: 10, marginTop: 16 },
  attachmentAction: { flex: 1, minHeight: 72, borderRadius: 18, alignItems: 'center', justifyContent: 'center', gap: 7, backgroundColor: C.glass, borderWidth: 1, borderColor: C.line },
  attachmentActionText: { color: C.text, fontSize: 11, fontWeight: '800' },
  send: { width: 48, height: 48, borderRadius: 24, backgroundColor: C.blue, alignItems: 'center', justifyContent: 'center' },
  sendDisabled: { opacity: 0.38 },
  });
}

function resolvedGridColor(colors: ThemeColors) {
  return colors === LIGHT_COLORS ? 'rgba(72,103,134,0.20)' : 'rgba(74,122,168,0.24)';
}

function resolvedMineGlass(colors: ThemeColors) {
  return colors === LIGHT_COLORS ? 'rgba(32,112,214,0.19)' : 'rgba(27,105,198,0.42)';
}
