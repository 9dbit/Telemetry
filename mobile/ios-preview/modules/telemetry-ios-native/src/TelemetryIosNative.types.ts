export type TelemetryIdentity = {
  deviceId: string;
  signingPublicKey: string;
  exchangePublicKey: string;
};

export type TelemetryCapabilities = {
  bluetooth: boolean;
  wifiPeerToPeer?: boolean;
  wifiTransport?: string;
  wifiAware: boolean;
  wifiAwareReason: string;
  platform: string;
};

export type AppearanceMode = 'system' | 'light' | 'dark';

export type LocalProfile = {
  displayName: string;
  about: string;
  photoUri?: string;
  templateId?: string;
  updatedAt: number;
};

export type Contact = {
  deviceId: string;
  peerId: string;
  alias: string;
  updatedAt: number;
  unreadCount?: number;
  profilePhotoUri?: string;
  profileDisplayName?: string;
  profileTemplateId?: string;
};

export type PersistedMessage = {
  id: string;
  peerDeviceId: string;
  text: string;
  mine: boolean;
  timestamp: number;
  delivered: boolean;
  attemptCount?: number;
  nextAttemptAt?: number;
  lastError?: string;
};

export type PersistedMedia = {
  assetId: string;
  peerDeviceId: string;
  kind: 'photo' | 'video' | 'file';
  fileName: string;
  byteLength: number;
  state: 'outgoingQueued' | 'outgoingProgress' | 'outgoingComplete' | 'incomingProgress' | 'incomingReady' | 'paused';
  totalChunks: number;
  acknowledgedChunks?: number;
  receivedChunks?: number;
  localUri?: string;
  sha256?: string;
  verified?: boolean;
  message?: string;
  updatedAt: number;
};

export type LocalState = {
  version: number;
  profile?: LocalProfile;
  appearance?: AppearanceMode;
  contacts: Contact[];
  messages: PersistedMessage[];
  media?: PersistedMedia[];
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

export type ProfileEvent = {
  peerId: string;
  deviceId: string;
  displayName?: string;
  templateId?: string;
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

export type CallSignalEvent = {
  peerId: string;
  deviceId: string;
  callId: string;
  action: 'invite' | 'accept' | 'offer' | 'answer' | 'ice' | 'end' | 'decline';
  mode: 'voice' | 'video';
  payload?: string;
};


export type StateEvent = {
  state: string;
  detail?: string;
};

export type ErrorEvent = { message: string };

export type NotificationOpenEvent = { deviceId: string };

export type MediaEvent = {
  state: 'outgoingQueued' | 'outgoingProgress' | 'outgoingComplete' | 'incomingProgress' | 'incomingReady' | 'paused';
  assetId: string;
  kind: 'photo' | 'video' | 'file';
  fileName: string;
  byteLength: number;
  peerDeviceId?: string;
  acknowledgedChunks?: number;
  receivedChunks?: number;
  totalChunks: number;
  transport?: string;
  localUri?: string;
  sha256?: string;
  verified?: boolean;
  message?: string;
};
