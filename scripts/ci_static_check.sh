#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"

fail() {
  printf '[ci-static] ERROR: %s\n' "$1" >&2
  exit 1
}

required_files=(
  build.gradle
  common/build.gradle
  fabric/build.gradle
  neoforge/build.gradle
  fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FabricPayloads.java
  fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FakeAiPlayerFabric.java
  fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/client/FakeAiPlayerFabricClient.java
  fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/client/FabricBuildingPreviewRenderer.java
  neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgePayloads.java
  neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgeGameEvents.java
  neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/client/NeoForgeClientSetup.java
  neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/client/NeoForgeBuildingPreviewRenderer.java
  fabric/src/gametest/resources/fabric.mod.json
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerDeterministicGameTests.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerHarnessTestMod.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotTestSubcommand.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotVerifySubcommand.java
  scripts/evidence_run.sh
  scripts/evidence_batch.sh
  scripts/evidence_validate.sh
  scripts/pin_baseline.sh
  scripts/persistence_restart_test.sh
  scripts/lib/harness.sh
  scripts/capability_matrix.sh
  reports/baselines/index.tsv
  docs/CAPABILITY_MATRIX.md
  .github/workflows/ci.yml
  .github/workflows/nightly.yml
  .github/workflows/manual-llm.yml
)

for file in "${required_files[@]}"; do
  [[ -f "$file" ]] || fail "missing required file: $file"
done

grep -Fq 'gametest {' fabric/build.gradle \
  || fail 'Fabric GameTest must use an isolated source set'
grep -Fq 'register("${mod_id}-gametest")' fabric/build.gradle \
  || fail 'Fabric GameTest mod id is not pinned'
grep -Fq "orElse('build/run/harness')" fabric/build.gradle \
  || fail 'Fabric harness default run directory is not module-local'
grep -Fq 'runHarnessServer' fabric/build.gradle \
  || fail 'command-driven harness run is missing'
grep -Fq 'must be a project-relative child directory' fabric/build.gradle \
  || fail 'harness run directory traversal guard is missing'
grep -Fq '"fabric-gametest"' fabric/src/gametest/resources/fabric.mod.json \
  || fail 'GameTest entrypoint is not registered'

[[ ! -e common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotTestSubcommand.java ]] \
  || fail 'test command leaked into production source set'
[[ ! -e common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotVerifySubcommand.java ]] \
  || fail 'verify command leaked into production source set'
if find common/src/main fabric/src/main neoforge/src/main -type f \
    \( -iname '*gametest*.java' -o -path '*/gametest/*' \) -print -quit | grep -q .; then
  fail 'GameTest implementation leaked into the production source set'
fi
if grep -RqE 'AIBot(Test|Verify)Subcommand|literal\("(test|verify)"\)' \
    common/src/main/java fabric/src/main/java neoforge/src/main/java; then
  fail 'production command graph references a verification harness'
fi

# The building preview protocol is shared, but every payload and lifecycle edge must be wired on
# both loaders. Keep this source-level gate explicit so a loader-specific feature cannot silently
# land on only one side again.
fabric_payloads=fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FabricPayloads.java
fabric_server=fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FakeAiPlayerFabric.java
fabric_client=fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/client/FakeAiPlayerFabricClient.java
fabric_renderer=fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/client/FabricBuildingPreviewRenderer.java
neoforge_payloads=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgePayloads.java
neoforge_events=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgeGameEvents.java
neoforge_client=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/client/NeoForgeClientSetup.java
neoforge_renderer=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/client/NeoForgeBuildingPreviewRenderer.java

building_c2s=(
  BuildingPreviewConfirmC2S
  BuildingPreviewCancelC2S
  BuildingPreviewReadyC2S
)
building_s2c=(
  BuildingPreviewBeginS2C
  BuildingPreviewChunkS2C
  BuildingPreviewCommitS2C
  BuildingPreviewClearS2C
)
for payload in "${building_c2s[@]}"; do
  grep -Fq "${payload}.ID" "$fabric_payloads" \
    || fail "Fabric is missing building C2S payload registration: $payload"
  grep -Fq "${payload}.ID" "$neoforge_payloads" \
    || fail "NeoForge is missing building C2S payload registration: $payload"
done
for payload in "${building_s2c[@]}"; do
  grep -Fq "${payload}.ID" "$fabric_payloads" \
    || fail "Fabric is missing building S2C payload type registration: $payload"
  grep -Fq "${payload}.ID" "$fabric_client" \
    || fail "Fabric is missing building S2C client receiver: $payload"
  grep -Fq "${payload}.ID" "$neoforge_payloads" \
    || fail "NeoForge is missing building S2C payload registration: $payload"
done
for handler in handleConfirm handleCancel handleReady; do
  grep -Fq "$handler" "$fabric_payloads" \
    || fail "Fabric is missing building preview server handler: $handler"
  grep -Fq "$handler" "$neoforge_payloads" \
    || fail "NeoForge is missing building preview server handler: $handler"
done
for key in confirmBuildingPreview cancelBuildingPreview; do
  grep -Fq "$key" "$fabric_client" \
    || fail "Fabric is missing building preview key registration: $key"
  grep -Fq "$key" "$neoforge_client" \
    || fail "NeoForge is missing building preview key registration: $key"
done
grep -Fq 'BuildingPreviewService.INSTANCE.onDisconnect(handler.player)' "$fabric_server" \
  || fail 'Fabric does not clear authoritative building preview sessions on disconnect'
grep -Fq 'BuildingPreviewService.INSTANCE.onDisconnect(player)' "$neoforge_events" \
  || fail 'NeoForge does not clear authoritative building preview sessions on disconnect'
grep -Fq 'FabricBuildingPreviewRenderer.register()' "$fabric_client" \
  || fail 'Fabric building preview renderer is not initialized'
grep -Fq 'WorldRenderEvents.AFTER_ENTITIES.register' "$fabric_renderer" \
  || fail 'Fabric building preview renderer is not attached to a world render event'
grep -Fq 'ClientPlayConnectionEvents.DISCONNECT.register' "$fabric_renderer" \
  || fail 'Fabric building preview client state is not cleared on disconnect'
grep -Fq 'RenderLevelStageEvent.Stage.AFTER_PARTICLES' "$neoforge_renderer" \
  || fail 'NeoForge building preview renderer is not attached to the expected render stage'
grep -Fq 'ClientPlayerNetworkEvent.LoggingOut' "$neoforge_renderer" \
  || fail 'NeoForge building preview client state is not cleared on logout'
for renderer in "$fabric_renderer" "$neoforge_renderer"; do
  for invariant in \
      MAX_RENDER_DISTANCE_SQUARED \
      BuildingPreviewClientState.INSTANCE.active \
      BlockStateResolver.resolve \
      ShapeRenderer.renderLineBox \
      CLEAR_CONFLICT \
      PRESERVE_CONFLICT; do
    grep -Fq "$invariant" "$renderer" \
      || fail "$renderer is missing building preview renderer invariant: $invariant"
  done
done

for script in scripts/*.sh scripts/lib/*.sh; do
  bash -n "$script" || fail "shell syntax failed: $script"
done

for workflow in .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/manual-llm.yml; do
  grep -Fq 'fetch-depth: 0' "$workflow" \
    || fail "$workflow must fetch full history for commit reachability validation"
  grep -Fq 'actions/upload-artifact@v4' "$workflow" \
    || fail "$workflow does not upload diagnostics"
  grep -Fq 'if: always()' "$workflow" \
    || fail "$workflow may discard diagnostics after a failure"
  if grep -Fq 'scripts/food_test.sh' "$workflow"; then
    fail "$workflow still invokes the legacy shared-run wrapper"
  fi
done

for workflow in .github/workflows/ci.yml .github/workflows/nightly.yml; do
  grep -Fq 'runGameTest' "$workflow" || fail "$workflow does not execute runGameTest"
  if grep -Fq 'DEEPSEEK_API_KEY' "$workflow"; then
    fail "$workflow must not have access to the billed LLM secret"
  fi
done
grep -Fq 'scripts/evidence_run.sh' .github/workflows/ci.yml \
  || fail 'PR CI does not run isolated runtime evidence'
grep -Fq 'scripts/evidence_batch.sh' .github/workflows/nightly.yml \
  || fail 'nightly does not run the profile/seed evidence matrix'
grep -Fq 'profile: [strict_survival, operator]' .github/workflows/nightly.yml \
  || fail 'nightly does not cover both operating profiles'

manual=.github/workflows/manual-llm.yml
grep -Fq 'workflow_dispatch:' "$manual" || fail 'manual LLM workflow is not dispatch-only'
if grep -Eq '^[[:space:]]+(push|pull_request|schedule):' "$manual"; then
  fail 'manual LLM workflow must never run automatically'
fi
grep -Fq 'secrets.DEEPSEEK_API_KEY' "$manual" \
  || fail 'manual LLM workflow does not receive its secret through GitHub Secrets'
grep -Fq 'confirm_billing:' "$manual" \
  || fail 'manual LLM workflow does not require explicit billing confirmation'
grep -Fq -- '--mode llm_story' "$manual" \
  || fail 'manual LLM workflow is not explicitly marked as billed evidence'

# When invoked after `build`, inspect every produced jar. Sources and production jars must both
# remain free of testmod classes and verification commands.
if [[ "${CI_STATIC_CHECK_ARTIFACTS:-0}" == 1 ]]; then
  command -v jar >/dev/null 2>&1 || fail 'artifact inspection requires the JDK jar command on PATH'
  [[ -d fabric/build/libs ]] || fail 'artifact inspection requested before fabric/build/libs exists'
  [[ -d neoforge/build/libs ]] || fail 'artifact inspection requested before neoforge/build/libs exists'
  find fabric/build/libs -maxdepth 1 -type f -name '*.jar' -print -quit | grep -q . \
    || fail 'artifact inspection found no Fabric jars'
  find neoforge/build/libs -maxdepth 1 -type f -name '*.jar' -print -quit | grep -q . \
    || fail 'artifact inspection found no NeoForge jars'
  inspected=0
  while IFS= read -r -d '' jar_file; do
    inspected=1
    if ! jar_listing="$(jar tf "$jar_file")"; then
      fail "invalid or truncated jar: $jar_file"
    fi
    if grep -Eq 'io/github/greytaiwolf/fakeaiplayer/(gametest/|command/AIBot(Test|Verify)Subcommand)' \
        <<< "$jar_listing"; then
      fail "verification harness leaked into jar: $jar_file"
    fi
  done < <(find common/build/libs fabric/build/libs neoforge/build/libs \
    -maxdepth 1 -type f -name '*.jar' -print0)
  [[ "$inspected" == 1 ]] || fail 'artifact inspection found no jars'
fi

bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md \
  || fail 'generated capability matrix is stale or its pinned evidence is invalid'

printf '[ci-static] OK: source-set, loader parity, workflow, shell and artifact invariants hold\n'
