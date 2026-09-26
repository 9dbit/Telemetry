# Telemetry P0 Test Matrix

## iOS physical devices
| Test | iPhone 15 Pro | iPhone 11 Pro | Required |
|---|---|---|---|
| App launch / current Release build | PENDING | PENDING | YES |
| BLE message send/receive | PENDING | PENDING | YES |
| Wi-Fi message send/receive | PENDING | PENDING | YES |
| Wi-Fi -> BLE -> Wi-Fi fallback | PENDING | PENDING | YES |
| Photo send/receive preview | PENDING | PENDING | YES |
| Photo SHA-256 verification | PENDING | PENDING | YES |
| Interrupted media resume | PENDING | PENDING | YES |
| Peer avatar sync | PENDING | PENDING | YES |
| Media picker regression | PENDING | PENDING | YES |
| Nearby List view | PENDING | PENDING | YES |
| Nearby Field view | PENDING | PENDING | YES |
| Light Mode regression | PENDING | PENDING | YES |
| Voice call | PENDING | PENDING | YES before voice P0 done |
| Video call | PENDING | PENDING | YES before video P0 done |

## Android physical devices
Use Android device #3 and #4 when connected.

| Test | Device #3 | Device #4 | Required |
|---|---|---|---|
| BLE trust/message | PENDING | PENDING | YES |
| Wi-Fi-local message | PENDING | PENDING | YES |
| Photo/file/video media | PENDING | PENDING | YES |
| 10 MB transfer | PENDING | PENDING | YES |
| 100 MB transfer | PENDING | PENDING | YES |
| Wi-Fi loss/resume | PENDING | PENDING | YES |
| Voice call | PENDING | PENDING | YES |
| Self-update A -> B | PENDING | N/A | YES |

## Mixed-platform
| Test | Status |
|---|---|
| iOS -> Android photo | PENDING |
| Android -> iOS photo | PENDING |
| iOS <-> Android document | PENDING |
| iOS <-> Android short video | PENDING |
| integrity verification both directions | PENDING |

## Cloud / CI gate
For every P0 implementation commit:
- relevant unit tests: PASS
- TypeScript/static checks where applicable: PASS
- Android assemble where applicable: PASS
- iOS simulator/native compile where applicable: PASS
- no physical acceptance inferred from simulator/CI success

Update this file only from real evidence. Use `PASS`, `FAIL`, `PENDING`, or `N/A`; add a commit/device note for failures.
