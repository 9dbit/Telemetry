module.exports = ({ config }) => ({
  ...config,
  ios: {
    ...config.ios,
    infoPlist: {
      ...(config.ios?.infoPlist || {}),
      NSBonjourServices: ['_telemetry._tcp'],
      NSLocalNetworkUsageDescription:
        'Telemetry uses the local network and peer-to-peer Wi-Fi to securely communicate with nearby Telemetry devices without internet.',
      NSPhotoLibraryUsageDescription:
        'Telemetry lets you choose photos and videos to send securely to trusted peers.',
    },
  },
});
