import { NativeModule, requireNativeModule } from 'expo';

export type TelemetryInteropSelfTestResult = {
  version: string;
  passed: boolean;
  checkCount: number;
  failureCount: number;
  failures: string[];
  safetyCode: string;
  sessionKeyFingerprint: string;
};

declare class TelemetryInteropSelfTestModule extends NativeModule {
  run(): TelemetryInteropSelfTestResult;
}

export default requireNativeModule<TelemetryInteropSelfTestModule>('TelemetryInteropSelfTest');
