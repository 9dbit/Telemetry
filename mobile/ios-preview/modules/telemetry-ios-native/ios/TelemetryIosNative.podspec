Pod::Spec.new do |s|
  s.name           = 'TelemetryIosNative'
  s.version        = '0.1.0'
  s.summary        = 'Telemetry iOS offline transport and cryptography bridge'
  s.description    = 'CoreBluetooth GATT and CryptoKit implementation of Telemetry M1B for the iOS preview app.'
  s.author         = 'Telemetry'
  s.homepage       = 'https://github.com/9dbit/Telemetry'
  s.license        = { :type => 'MIT' }
  s.platform       = :ios, '16.4'
  s.source         = { :git => 'https://github.com/9dbit/Telemetry.git' }
  s.static_framework = true
  s.dependency 'ExpoModulesCore'
  s.source_files   = '**/*.{h,m,mm,swift}'
  s.resources      = ['cleng.wav']
  s.frameworks     = 'CoreBluetooth', 'CryptoKit', 'Security', 'Network'
  s.swift_version  = '5.9'
end
