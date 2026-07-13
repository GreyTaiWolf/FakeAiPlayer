#!/usr/bin/env bash
# 探针:验证无头 dedicated server 能起 + console 命令能通过 FIFO 注入。
# 用途:自动化测试 harness 的可行性验证(不是最终 harness)。
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT" || exit 1
LOG="${FAKEAIPLAYER_HARNESS_LOG:-${TMPDIR:-/tmp}/fakeaiplayer_harness.log}"
FIFO="${FAKEAIPLAYER_HARNESS_FIFO:-${TMPDIR:-/tmp}/fakeaiplayer_harness.fifo}"
HARNESS_RUN_DIR="${FAKEAIPLAYER_HARNESS_RUN_DIR:-build/run/harness}"
: > "$LOG"
rm -f "$FIFO"; mkfifo "$FIFO"

# 保持 FIFO 写端常开,否则 server 读到 EOF 会关闭 console
sleep 100000 > "$FIFO" &
HOLDER=$!

# 无头服务端;--no-daemon 让 stdin 直通到 server console
./gradlew --no-daemon --console=plain \
  -PfakeaiplayerHarnessRunDir="$HARNESS_RUN_DIR" :fabric:runHarnessServer \
  < "$FIFO" >> "$LOG" 2>&1 &
SRV=$!
echo "[harness] server pid=$SRV holder=$HOLDER"

READY=0
for i in $(seq 1 480); do
  if grep -q 'Done (' "$LOG" 2>/dev/null; then READY=1; echo "[harness] READY at ${i}s"; break; fi
  if ! kill -0 "$SRV" 2>/dev/null; then echo "[harness] SERVER EXITED EARLY at ${i}s"; break; fi
  sleep 1
done

if [ "$READY" = 1 ]; then
  echo "[harness] === injecting test commands ==="
  echo "say HARNESS_PING_123" > "$FIFO"; sleep 2
  echo "fakeaiplayer spawn Bob assistant" > "$FIFO"; sleep 6
  echo "fakeaiplayer list" > "$FIFO"; sleep 3
  echo "[harness] === command injection done ==="
fi

echo "[harness] stopping server"
echo "stop" > "$FIFO"; sleep 12
kill "$SRV" 2>/dev/null
kill "$HOLDER" 2>/dev/null
rm -f "$FIFO"
echo "[harness] FINISHED ready=$READY"
