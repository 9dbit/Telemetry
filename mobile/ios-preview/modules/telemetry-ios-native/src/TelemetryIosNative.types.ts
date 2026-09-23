export type TelemetryIdentity = {
  deviceId: string;
  signingPublicKey: string;
  exchangePublicKey: string;
};

export type TelemetryCapabilities = {
  bluetooth: boolean;
  wifiAware: boolean;
  wifiAwareReason: string;
  platform: string;
};

export type PeerSeenEvent = {
  peerId: string;
  name?: string;
  rssi: number;
};

export type VerificationEvent = {
  peerId: string;
  deviceId: string;
  safetyCode: string;
};

export type TrustedEvent = {
  peerId: string;
  deviceId: string;
};

export type MessageEvent = {
  peerId: string;
  deviceId: string;
  messageId: string;
  text: string;
};

export type DeliveryEvent = {
  peerId: string;
  messageId: string;
};

export type StateEvent = {
  state: string;
  detail?: string;
};

export type ErrorEvent = { message: string };
