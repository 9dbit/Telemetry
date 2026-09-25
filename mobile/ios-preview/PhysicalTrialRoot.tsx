import React, { useEffect, useMemo, useState } from 'react';
import {
  Modal,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  Share,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import App from './App';
import Telemetry, {
  TelemetryInteropSelfTest,
  type TelemetryInteropSelfTestResult,
} from 'telemetry-ios-native';

type TrialStatus = 'info' | 'pass' | 'fail';
type TrialLog = {
  id: string;
  at: number;
  stage: string;
  status: TrialStatus;
  detail?: string;
};

type TrustedPeer = { peerId: string; deviceId: string };

const stages = [
  ['crypto-self-test', 'Crypto interop vector'],
  ['peer-seen', 'BLE peer discovered'],
  ['gatt-connected', 'GATT connected'],
  ['secure-handshake-ready', 'Signed hello + session key'],
  ['peer-trusted', 'Safety code trusted'],
  ['trial-message-sent', 'Hello Telemetry sent'],
  ['encrypted-message-received', 'Encrypted message received'],
  ['signed-receipt-verified', 'Signed DELIVERED receipt'],
] as const;

export default function PhysicalTrialRoot() {
  const [open, setOpen] = useState(false);
  const [logs, setLogs] = useState<TrialLog[]>([]);
  const [trustedPeer, setTrustedPeer] = useState<TrustedPeer | null>(null);
  const [selfTest, setSelfTest] = useState<TelemetryInteropSelfTestResult | null>(null);
  const [selfTestError, setSelfTestError] = useState<string | null>(null);
  const identity = useMemo(() => Telemetry.getIdentity(), []);

  function record(stage: string, status: TrialStatus, detail?: string) {
    setLogs(current => [
      ...current.slice(-149),
      {
        id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
        at: Date.now(),
        stage,
        status,
        detail,
      },
    ]);
  }

  useEffect(() => {
    try {
      const result = TelemetryInteropSelfTest.run();
      setSelfTest(result);
      record(
        'crypto-self-test',
        result.passed ? 'pass' : 'fail',
        result.passed
          ? `${result.checkCount}/${result.checkCount} vector checks · safety ${result.safetyCode}`
          : `${result.failureCount} vector check(s) failed: ${result.failures.join(', ')}`,
      );
    } catch (error) {
      const message = String(error);
      setSelfTestError(message);
      record('crypto-self-test', 'fail', message);
    }

    const subscriptions = [
      Telemetry.addListener('onState', event => {
        if (event.state === 'connected') {
          record('gatt-connected', 'pass', 'CoreBluetooth GATT connection established');
        } else if (event.state === 'bluetooth-off') {
          record('bluetooth', 'fail', 'Bluetooth is powered off');
        } else if (event.state === 'offline' || event.state === 'scanning') {
          record('offline-radio-ready', 'pass', event.detail ?? event.state);
        }
      }),
      Telemetry.addListener('onPeerSeen', event => {
        record('peer-seen', 'pass', `${event.name ?? 'Telemetry peer'} · ${event.rssi} dBm`);
      }),
      Telemetry.addListener('onVerification', event => {
        record(
          'secure-handshake-ready',
          'pass',
          `Signed hello accepted · session derived · safety ${event.safetyCode}`,
        );
      }),
      Telemetry.addListener('onTrusted', event => {
        setTrustedPeer(event);
        record('peer-trusted', 'pass', `Trusted peer …${event.deviceId.slice(-8)}`);
      }),
      Telemetry.addListener('onMessage', event => {
        setTrustedPeer({ peerId: event.peerId, deviceId: event.deviceId });
        record('encrypted-message-received', 'pass', `Message ${shortMessageId(event.messageId)} decrypted locally`);
      }),
      Telemetry.addListener('onDelivery', event => {
        record('signed-receipt-verified', 'pass', `Receipt verified for ${shortMessageId(event.messageId)}`);
      }),
      Telemetry.addListener('onError', event => {
        record('native-error', 'fail', event.message);
      }),
    ];

    return () => subscriptions.forEach(subscription => subscription.remove());
  }, []);

  async function sendTrialHello() {
    if (!trustedPeer) {
      record('trial-message-sent', 'fail', 'No trusted BLE peer is active');
      return;
    }
    try {
      const messageId = await Telemetry.sendText(trustedPeer.peerId, 'Hello Telemetry');
      record('trial-message-sent', 'pass', `Encrypted frame ${shortMessageId(messageId)} written over GATT`);
    } catch (error) {
      record('trial-message-sent', 'fail', String(error));
    }
  }

  function resetTrial() {
    setLogs([]);
    setTrustedPeer(null);
    if (selfTest) {
      setLogs([
        {
          id: `reset-${Date.now()}`,
          at: Date.now(),
          stage: 'crypto-self-test',
          status: selfTest.passed ? 'pass' : 'fail',
          detail: selfTest.passed
            ? `${selfTest.checkCount}/${selfTest.checkCount} vector checks`
            : selfTest.failures.join(', '),
        },
      ]);
    }
  }

  async function shareDiagnostics() {
    const report = {
      reportVersion: 'telemetry-physical-trial/1',
      generatedAt: new Date().toISOString(),
      platform: Platform.OS,
      osVersion: String(Platform.Version),
      localDeviceIdSuffix: identity.deviceId.slice(-8),
      trustedPeerIdSuffix: trustedPeer?.deviceId.slice(-8) ?? null,
      cryptoSelfTest: selfTest
        ? {
            passed: selfTest.passed,
            checkCount: selfTest.checkCount,
            failureCount: selfTest.failureCount,
            failures: selfTest.failures,
            safetyCode: selfTest.safetyCode,
            sessionKeyFingerprint: selfTest.sessionKeyFingerprint,
          }
        : { passed: false, error: selfTestError },
      privacy: {
        messagePlaintextIncluded: false,
        privateKeysIncluded: false,
        publicKeysIncluded: false,
        fullStableDeviceIdsIncluded: false,
      },
      events: logs.map(item => ({
        at: new Date(item.at).toISOString(),
        stage: item.stage,
        status: item.status,
        detail: item.detail,
      })),
    };
    await Share.share({
      title: 'Telemetry Physical Trial M1 diagnostics',
      message: JSON.stringify(report, null, 2),
    });
  }

  const latestStatus = useMemo(() => {
    const byStage = new Map<string, TrialLog>();
    for (const item of logs) byStage.set(item.stage, item);
    return byStage;
  }, [logs]);

  return (
    <View style={styles.root}>
      <App />
      <Modal visible={open} animationType="slide" onRequestClose={() => setOpen(false)}>
        <SafeAreaView style={styles.safe}>
          <View style={styles.header}>
            <View>
              <Text style={styles.eyebrow}>PHYSICAL TRIAL M1</Text>
              <Text style={styles.title}>Offline interoperability</Text>
            </View>
            <Pressable onPress={() => setOpen(false)}><Text style={styles.link}>Close</Text></Pressable>
          </View>

          <ScrollView contentContainerStyle={styles.content}>
            <View style={styles.notice}>
              <Text style={styles.noticeTitle}>Internet must stay OFF during radio acceptance.</Text>
              <Text style={styles.copy}>Bluetooth stays ON. This console records protocol milestones only, never message plaintext or private keys.</Text>
            </View>

            <Text style={styles.section}>Preflight</Text>
            <TrialRow
              label="CryptoKit ↔ Android vector"
              state={selfTest?.passed ? 'pass' : selfTestError || selfTest ? 'fail' : 'info'}
              detail={selfTest?.passed ? `${selfTest.checkCount} deterministic checks passed` : selfTestError ?? 'Running…'}
            />
            <TrialRow label="Local device" state="info" detail={`…${identity.deviceId.slice(-8)} · iOS ${String(Platform.Version)}`} />

            <Text style={styles.section}>Radio + trust + message</Text>
            {stages.slice(1).map(([key, label]) => {
              const item = latestStatus.get(key);
              return <TrialRow key={key} label={label} state={item?.status ?? 'info'} detail={item?.detail ?? 'Pending'} />;
            })}

            <Pressable style={[styles.primary, !trustedPeer && styles.disabled]} onPress={sendTrialHello}>
              <Text style={styles.primaryText}>Send “Hello Telemetry”</Text>
            </Pressable>
            <Text style={styles.copy}>Button activates after the peer is explicitly trusted. Delivery is only PASS after a valid signed receipt event.</Text>

            <View style={styles.actions}>
              <Pressable style={styles.secondary} onPress={shareDiagnostics}><Text style={styles.secondaryText}>Share diagnostics</Text></Pressable>
              <Pressable style={styles.secondary} onPress={resetTrial}><Text style={styles.secondaryText}>Reset leg</Text></Pressable>
            </View>

            <Text style={styles.section}>Event log</Text>
            {logs.length === 0 ? (
              <Text style={styles.copy}>No trial events yet.</Text>
            ) : (
              [...logs].reverse().map(item => (
                <View key={item.id} style={styles.logRow}>
                  <Text style={styles.logTime}>{new Date(item.at).toLocaleTimeString()}</Text>
                  <View style={styles.logBody}>
                    <Text style={[styles.logStage, item.status === 'fail' && styles.failText]}>{item.stage}</Text>
                    {!!item.detail && <Text style={styles.logDetail}>{item.detail}</Text>}
                  </View>
                </View>
              ))
            )}
          </ScrollView>
        </SafeAreaView>
      </Modal>
    </View>
  );
}

function TrialRow({ label, state, detail }: { label: string; state: TrialStatus; detail: string }) {
  const symbol = state === 'pass' ? '✓' : state === 'fail' ? '!' : '·';
  return (
    <View style={styles.row}>
      <View style={[styles.statusIcon, state === 'pass' ? styles.pass : state === 'fail' ? styles.fail : styles.pending]}>
        <Text style={styles.statusText}>{symbol}</Text>
      </View>
      <View style={styles.rowBody}>
        <Text style={styles.rowLabel}>{label}</Text>
        <Text style={styles.rowDetail}>{detail}</Text>
      </View>
    </View>
  );
}

function shortMessageId(value: string) {
  return `${value.slice(0, 8)}…${value.slice(-4)}`;
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  safe: { flex: 1, backgroundColor: '#07111F' },
  fab: {
    position: 'absolute',
    right: 14,
    top: 82,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    backgroundColor: '#13243AEE',
    borderColor: '#2A4C70',
    borderWidth: 1,
    borderRadius: 99,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  fabText: { color: '#F4F8FF', fontWeight: '800', fontSize: 10, letterSpacing: 0.8 },
  fabDot: { width: 7, height: 7, borderRadius: 99 },
  header: {
    paddingHorizontal: 20,
    paddingVertical: 14,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#20344D',
  },
  eyebrow: { color: '#38E2C2', fontWeight: '800', fontSize: 10, letterSpacing: 1.2 },
  title: { color: '#F4F8FF', fontWeight: '800', fontSize: 22, marginTop: 3 },
  link: { color: '#2F86FF', fontWeight: '800' },
  content: { padding: 20, paddingBottom: 48 },
  notice: { backgroundColor: '#10253A', borderRadius: 16, padding: 15, borderWidth: 1, borderColor: '#1D5A65' },
  noticeTitle: { color: '#F4F8FF', fontWeight: '800', fontSize: 14 },
  copy: { color: '#93A8C2', fontSize: 12, lineHeight: 18, marginTop: 5 },
  section: { color: '#93A8C2', fontWeight: '800', fontSize: 10, letterSpacing: 1.1, marginTop: 22, marginBottom: 8 },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomColor: '#172B42', borderBottomWidth: StyleSheet.hairlineWidth },
  statusIcon: { width: 28, height: 28, borderRadius: 14, alignItems: 'center', justifyContent: 'center', marginRight: 10 },
  pass: { backgroundColor: '#175C50' },
  fail: { backgroundColor: '#7A2632' },
  pending: { backgroundColor: '#23384F' },
  statusText: { color: '#FFF', fontWeight: '900' },
  rowBody: { flex: 1 },
  rowLabel: { color: '#F4F8FF', fontSize: 13, fontWeight: '700' },
  rowDetail: { color: '#7F96B0', fontSize: 10, marginTop: 3, lineHeight: 15 },
  primary: { marginTop: 20, height: 52, borderRadius: 15, alignItems: 'center', justifyContent: 'center', backgroundColor: '#2F86FF' },
  disabled: { opacity: 0.42 },
  primaryText: { color: '#FFF', fontWeight: '800' },
  actions: { flexDirection: 'row', gap: 10, marginTop: 12 },
  secondary: { flex: 1, height: 44, borderRadius: 13, alignItems: 'center', justifyContent: 'center', backgroundColor: '#12243A' },
  secondaryText: { color: '#B8CCE3', fontWeight: '700', fontSize: 12 },
  logRow: { flexDirection: 'row', paddingVertical: 8 },
  logTime: { width: 72, color: '#5F7895', fontSize: 9, fontFamily: 'Menlo' },
  logBody: { flex: 1 },
  logStage: { color: '#38E2C2', fontWeight: '700', fontSize: 11 },
  failText: { color: '#FF8794' },
  logDetail: { color: '#7890AB', fontSize: 10, lineHeight: 15, marginTop: 2 },
});
