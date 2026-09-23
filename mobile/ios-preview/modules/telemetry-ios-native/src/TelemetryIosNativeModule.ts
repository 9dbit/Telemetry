import { NativeModule, requireNativeModule } from 'expo';
import type {
  DeliveryEvent,
  ErrorEvent,
  MessageEvent,
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
  onDelivery(event: DeliveryEvent): void;
  onError(event: ErrorEvent): void;
};

declare class TelemetryIosNativeModule extends NativeModule<TelemetryEvents> {
  getIdentity(): TelemetryIdentity;
  getCapabilities(): TelemetryCapabilities;
  startOffline(): Promise<void>;
  stopOffline(): Promise<void>;
  connect(peerId: string): Promise<void>;
  trustPeer(deviceId: string): Promise<boolean>;
  sendText(peerId: string, text: string): Promise<string>;
}

export default requireNativeModule<TelemetryIosNativeModule>('TelemetryIosNative');
