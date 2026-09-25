#!/bin/zsh
set -euo pipefail

ROOT="${0:A:h:h}"
BUNDLE_ID="com.telemetry.ios.preview"
SENDER_ID="${TELEMETRY_SENDER_ID:-223FE5CF-703A-5830-81C3-2CA9B0980B15}"
RECEIVER_ID="${TELEMETRY_RECEIVER_ID:-CB375AD3-0B2F-595D-B316-D19CCAF9470F}"
APP_PATH="${TELEMETRY_APP_PATH:-/Users/mac/Library/Developer/Xcode/DerivedData/TelemetryiOSPreview-fvqbhdpdqyqiqoceqqvplukzgizt/Build/Products/Release-iphoneos/TelemetryiOSPreview.app}"
LOG_DIR="${TMPDIR:-/tmp}/telemetry-reliability-$$"
mkdir -p "$LOG_DIR"

say() { print "[reliability] $*"; }
fail() { print -u2 "[reliability] FAIL: $*"; exit 1; }

require_device() {
  local id="$1" label="$2" line
  line="$(xcrun devicectl list devices | grep "$id" || true)"
  [[ -n "$line" ]] || fail "$label was not found"
  print "$line" | grep -q 'unavailable' && fail "$label is unavailable"
}

app_pid() {
  xcrun devicectl device info processes --device "$1" 2>/dev/null | \
    awk '/TelemetryiOSPreview\.app\/TelemetryiOSPreview/{print $1; exit}'
}
stop_app() {
  local id="$1" pid
  pid="$(app_pid "$id" || true)"
  if [[ -n "$pid" ]]; then
    xcrun devicectl device process terminate --device "$id" --pid "$pid" >/dev/null 2>&1 || true
  fi
}

install_app() {
  xcrun devicectl device install app --device "$1" "$APP_PATH" >/dev/null
}

launch_console() {
  local id="$1" log="$2"; shift 2
  xcrun devicectl device process launch --terminate-existing --console \
    --device "$id" "$BUNDLE_ID" "$@" >"$log" 2>&1 &
  print $!
}

wait_log() {
  local log="$1" pattern="$2" timeout="${3:-45}" elapsed=0
  while (( elapsed < timeout )); do
    grep -q "$pattern" "$log" 2>/dev/null && return 0
    sleep 1; (( elapsed += 1 ))
  done
  return 1
}
cleanup() {
  stop_app "$SENDER_ID" || true
  stop_app "$RECEIVER_ID" || true
}
trap cleanup EXIT INT TERM

[[ -d "$APP_PATH" ]] || fail "Release app not found at $APP_PATH"
require_device "$SENDER_ID" "sender"
require_device "$RECEIVER_ID" "receiver"

say "installing identical Release build on both devices"
stop_app "$SENDER_ID"; stop_app "$RECEIVER_ID"
install_app "$SENDER_ID"; install_app "$RECEIVER_ID"

say "phase 1: 100-message endurance + exact-frame replay"
RECEIVER_LOG="$LOG_DIR/phase1-receiver.log"
SENDER_LOG="$LOG_DIR/phase1-sender.log"
launch_console "$RECEIVER_ID" "$RECEIVER_LOG" --telemetry-reliability-log >/dev/null
sleep 2
launch_console "$SENDER_ID" "$SENDER_LOG" \
  --telemetry-reliability-log \
  --telemetry-reliability-count=100 \
  --telemetry-reliability-replay-after >/dev/null

wait_log "$SENDER_LOG" 'ENDURANCE_COMPLETE.*delivered=100/100' 90 || fail "100-message sender completion not observed"
wait_log "$RECEIVER_LOG" 'acceptedIncoming=100' 20 || fail "receiver did not accept 100 messages"
wait_log "$RECEIVER_LOG" 'duplicateFrameSuppressed=1' 20 || fail "replay frame was not suppressed"
say "PASS phase 1: 100 receipts, 100 accepted, replay suppressed"
say "phase 2: queue while receiver radio paused, crash sender, relaunch both"
stop_app "$SENDER_ID"; stop_app "$RECEIVER_ID"
PHASE2_OFF_LOG="$LOG_DIR/phase2-receiver-off.log"
PHASE2_QUEUE_LOG="$LOG_DIR/phase2-sender-queue.log"
launch_console "$RECEIVER_ID" "$PHASE2_OFF_LOG" --telemetry-radio-paused >/dev/null
sleep 2
launch_console "$SENDER_ID" "$PHASE2_QUEUE_LOG" \
  --telemetry-reliability-log \
  --telemetry-reliability-count=10 >/dev/null
sleep 6
if grep -q 'deliveryReceiptsAccepted=' "$PHASE2_QUEUE_LOG"; then
  fail "receiver radio-paused phase unexpectedly delivered messages"
fi
say "10 messages remained queued while receiver transport was unavailable"

stop_app "$SENDER_ID"; stop_app "$RECEIVER_ID"
PHASE2_RECEIVER_LOG="$LOG_DIR/phase2-receiver-recovered.log"
PHASE2_SENDER_LOG="$LOG_DIR/phase2-sender-recovered.log"
launch_console "$RECEIVER_ID" "$PHASE2_RECEIVER_LOG" --telemetry-reliability-log >/dev/null
sleep 2
launch_console "$SENDER_ID" "$PHASE2_SENDER_LOG" --telemetry-reliability-log >/dev/null

wait_log "$PHASE2_SENDER_LOG" 'deliveryReceiptsAccepted=10' 60 || fail "restored queue did not reach 10 signed receipts"
wait_log "$PHASE2_RECEIVER_LOG" 'acceptedIncoming=10' 20 || fail "receiver did not accept restored 10-message queue"
say "PASS phase 2: encrypted queue survived sender crash/relaunch and auto-delivered"
say "ALL CORE M1.2D HARDWARE CHECKS PASSED"
say "logs: $LOG_DIR"
