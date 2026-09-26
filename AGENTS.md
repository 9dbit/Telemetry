# Telemetry Agent Operating Rules

## Source of truth
GitHub repository `9dbit/Telemetry` is the source of truth. Before changing code, read:
1. `TELEMETRY_MASTER.md`
2. `ACTIVE_TASKS.md`
3. `TEST_MATRIX.md`
4. the relevant implementation and tests

Do not reconstruct project state from chat history when repository state is available.

## Execution policy
- Continue the highest-priority unblocked P0 item first.
- Inspect existing implementation before writing new code.
- Prefer the smallest change that satisfies the acceptance criteria.
- Do not recreate working modules or create competing message/security/network pipelines.
- Keep commits focused and reversible.
- Run the relevant unit/build/static checks before every push.
- Never claim physical-device acceptance without actual device evidence.
- If hardware is unavailable, complete cloud/simulator work, record the exact remaining hardware gate, then continue with the next unblocked P0 task.
- Keep draft PR dependency order intact. Do not merge a stacked PR ahead of its base dependency.

## Product rules
- UI language is English.
- Primary brand color is ocean blue.
- Opening a chat should land at the newest/bottom message.
- Test messaging must clearly distinguish BLE vs Wi-Fi.
- Nearby keeps both List and Field views.
- Peer/contact UI supports profile photos.
- Avoid overlapping badges, controls, or headers.
- Media messages must show real local/remote preview state, transfer progress, completion, failure, retry and verified integrity.

## Architecture rules
- Offline-first operation is mandatory.
- Local messaging must not depend on Railway/cloud availability.
- End-to-end encrypted/signed payloads remain transport-agnostic.
- Relay nodes carry opaque encrypted envelopes wherever possible.
- BLE is appropriate for discovery/control/small frames; large media prefers Wi-Fi-class transport.
- Mesh, LoRa and satellite may relay signaling/store-and-forward data, but are not assumed to carry realtime audio/video.
- Do not expose stable device identifiers through unnecessary local discovery broadcasts.
- Do not weaken trust, replay, expiry, signature or integrity checks to make a test pass.

## Done means
A task is complete only when its acceptance criteria in `ACTIVE_TASKS.md` and `TEST_MATRIX.md` are satisfied. Code complete but hardware unverified is `READY_FOR_DEVICE_ACCEPTANCE`, not `DONE`.
