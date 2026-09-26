import { NativeModule, requireNativeModule } from 'expo';
import type {
  CallSignalEvent,
  DeliveryEvent,
  ErrorEvent,
  MessageEvent,
  MediaEvent,
  NotificationOpenEvent,
  ProfileEvent,
  PeerSeenEvent,
  StateEvent,
  TelemetryCapabilities,
  TelemetryIdentity,
  TrustedEvent,
  VerificationEvent,
} from './TelemetryIosNative.types';

type TelemetryEvents = {
  onState(event: StateEvent): void;
  onPeerSeen(event: PeerSeenEvent): void;
  onVerification(event: VerificationEvent): void;
  onTrusted(event: TrustedEvent): void;
  onMessage(event: MessageEvent): void;
  onProfile(event: ProfileEvent): void;
  onDelivery(event: DeliveryEvent): void;
  onMedia(event: MediaEvent): void;
  onCallSignal(event: CallSignalEvent): void;
  onNotificationOpen(event: NotificationOpenEvent): void;
  onError(event: ErrorEvent): void;
};

declare class TelemetryIosNativeModule extends NativeModule<TelemetryEvents> {
  getIdentity(): TelemetryIdentity;
  getCapabilities(): TelemetryCapabilities;
  getLocalState(): string;
  getLaunchArguments(): string[];
  getReliabilityDiagnostics(): string;
  resetReliabilityDiagnostics(): void;
  replayLastEncryptedFrameForTest(peerId: string): Promise<boolean>;
  consumePendingNotificationOpen(): string | null;
  setLocalProfile(displayName: string, about: string, sourcePhotoUri?: string | null, templateId?: string | null): Promise<{ displayName: string; about: string; photoUri?: string; templateId?: string; updatedAt: number }>;
  setAppearance(mode: 'system' | 'light' | 'dark'): Promise<'system' | 'light' | 'dark'>;
  setContactAlias(deviceId: string, alias: string): Promise<boolean>;
  markConversationRead(deviceId: string): Promise<boolean>;
  setContactProfilePhoto(deviceId: string, photoUri: string): Promise<boolean>;
  startOffline(): Promise<void>;
  stopOffline(): Promise<void>;
  connect(peerId: string): Promise<void>;
  probePeer(peerId: string): Promise<void>;
  recoverTransport(peerId: string): Promise<void>;
  trustPeer(deviceId: string): Promise<boolean>;
  enqueueText(peerId: string, peerDeviceId: string, text: string): Promise<string>;
  sendProfile(peerId: string, peerDeviceId: string, displayName: string, templateId?: string | null): Promise<string>;
  sendQueuedText(peerId: string, messageId: string): Promise<string>;
  sendQueuedTextUsingTransport(peerId: string, messageId: string, transport: 'auto' | 'ble' | 'wifi'): Promise<string>;
  sendCallSignal(peerId: string, peerDeviceId: string, callId: string, action: 'invite' | 'accept' | 'offer' | 'answer' | 'ice' | 'end' | 'decline', mode: 'voice' | 'video', payload?: string | null): Promise<string>;
  sendMedia(peerDeviceId: string, uri: string, kind: 'photo' | 'video' | 'file', mimeType: string, fileName: string): Promise<string>;
  resumeMedia(peerDeviceId?: string | null): Promise<number>;
  sendText(peerId: string, text: string): Promise<string>;
}

export default requireNativeModule<TelemetryIosNativeModule>('TelemetryIosNative');
