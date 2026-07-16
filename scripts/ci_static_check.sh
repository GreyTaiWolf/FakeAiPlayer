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
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/inventory/BotInventoryMenu.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/inventory/BotInventorySessionManager.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/network/payload/OpenBotInventoryC2S.java
  fabric/build.gradle
  neoforge/build.gradle
  fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FabricMenuRegistry.java
  fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FabricPayloads.java
  fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FakeAiPlayerFabric.java
  fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/client/FakeAiPlayerFabricClient.java
  fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/client/FabricBuildingPreviewRenderer.java
  neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgePayloads.java
  neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgeMenuRegistry.java
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

# Payload types live in common, but every C2S/S2C edge must be registered by both loaders. Derive
# the expected catalog from the shared source filenames so a new payload cannot silently land on
# only one side. Feature-specific checks below additionally verify the handler/lifecycle wiring.
payload_dir=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/network/payload
fabric_payloads=fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FabricPayloads.java
fabric_server=fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FakeAiPlayerFabric.java
fabric_client=fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/client/FakeAiPlayerFabricClient.java
fabric_renderer=fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/client/FabricBuildingPreviewRenderer.java
fabric_menu=fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FabricMenuRegistry.java
neoforge_payloads=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgePayloads.java
neoforge_entry=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/FakeAiPlayerNeoForge.java
neoforge_events=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgeGameEvents.java
neoforge_client=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/client/NeoForgeClientSetup.java
neoforge_renderer=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/client/NeoForgeBuildingPreviewRenderer.java
neoforge_menu=neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgeMenuRegistry.java

mapfile -t c2s_payloads < <(
  find "$payload_dir" -maxdepth 1 -type f -name '*C2S.java' -printf '%f\n' \
    | sed 's/\.java$//' | sort
)
mapfile -t s2c_payloads < <(
  find "$payload_dir" -maxdepth 1 -type f -name '*S2C.java' -printf '%f\n' \
    | sed 's/\.java$//' | sort
)
(( ${#c2s_payloads[@]} > 0 )) || fail 'shared C2S payload catalog is empty'
(( ${#s2c_payloads[@]} > 0 )) || fail 'shared S2C payload catalog is empty'

for payload in "${c2s_payloads[@]}"; do
  [[ "$(grep -Fc "${payload}.ID" "$fabric_payloads")" -ge 2 ]] \
    || fail "Fabric is missing C2S type registration or receiver: $payload"
  grep -Fq "${payload}.ID" "$neoforge_payloads" \
    || fail "NeoForge is missing C2S registration/handler: $payload"
done
for payload in "${s2c_payloads[@]}"; do
  grep -Fq "${payload}.ID" "$fabric_payloads" \
    || fail "Fabric is missing S2C type registration: $payload"
  grep -Fq "${payload}.ID" "$fabric_client" \
    || fail "Fabric is missing S2C client receiver: $payload"
  grep -Fq "${payload}.ID" "$neoforge_payloads" \
    || fail "NeoForge is missing S2C registration/handler: $payload"
done

# Protocol v3 replaces direct item moves with a server-authoritative vanilla Menu. Check the full
# loader edge: type/handler, menu registration, client screen and disconnect lease cleanup.
grep -Fq 'PROTOCOL_VERSION = "3"' "$neoforge_payloads" \
  || fail 'NeoForge inventory protocol version was not bumped to 3'
for payloads in "$fabric_payloads" "$neoforge_payloads"; do
  grep -Fq 'OpenBotInventoryC2S.ID' "$payloads" \
    || fail "$payloads is missing OpenBotInventoryC2S"
  grep -Fq 'handleOpenInventory' "$payloads" \
    || fail "$payloads is missing the server inventory-open handler"
done
grep -Fq 'FabricMenuRegistry.register()' "$fabric_server" \
  || fail 'Fabric bot inventory menu registry is not initialized'
grep -Fq 'Registry.register(' "$fabric_menu" \
  || fail 'Fabric bot inventory menu type is not registered'
grep -Fq 'MenuScreens.register(BotMenuTypes.inventory(), BotInventoryScreen::new)' "$fabric_client" \
  || fail 'Fabric bot inventory screen is not registered'
grep -Fq 'NeoForgeMenuRegistry.register(modEventBus)' "$neoforge_entry" \
  || fail 'NeoForge bot inventory menu registry is not initialized'
grep -Fq 'MENUS.register(modEventBus)' "$neoforge_menu" \
  || fail 'NeoForge bot inventory menu type is not registered'
grep -Fq 'event.register(BotMenuTypes.inventory(), BotInventoryScreen::new)' "$neoforge_client" \
  || fail 'NeoForge bot inventory screen is not registered'
grep -Fq 'BotInventorySessionManager.INSTANCE.onViewerDisconnect(handler.player)' "$fabric_server" \
  || fail 'Fabric does not release bot inventory sessions on disconnect'
grep -Fq 'BotInventorySessionManager.INSTANCE.onViewerDisconnect(player)' "$neoforge_events" \
  || fail 'NeoForge does not release bot inventory sessions on disconnect'

# Building preview has additional lifecycle, render and explicit-confirmation invariants beyond
# the generic payload catalog.

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

  mapfile -t fabric_production_jars < <(
    find fabric/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' | sort
  )
  mapfile -t neoforge_production_jars < <(
    find neoforge/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' | sort
  )
  [[ ${#fabric_production_jars[@]} -eq 1 ]] \
    || fail "expected exactly one Fabric production jar, found ${#fabric_production_jars[@]}"
  [[ ${#neoforge_production_jars[@]} -eq 1 ]] \
    || fail "expected exactly one NeoForge production jar, found ${#neoforge_production_jars[@]}"
  fabric_jar=${fabric_production_jars[0]}
  neoforge_jar=${neoforge_production_jars[0]}
  fabric_listing="$(jar tf "$fabric_jar")"
  neoforge_listing="$(jar tf "$neoforge_jar")"

  grep -Fxq 'fabric.mod.json' <<< "$fabric_listing" \
    || fail 'Fabric production jar is missing fabric.mod.json'
  grep -Fxq 'fakeaiplayer.refmap.json' <<< "$fabric_listing" \
    || fail 'Fabric production jar is missing its Mixin refmap'
  if grep -Fxq 'META-INF/neoforge.mods.toml' <<< "$fabric_listing"; then
    fail 'Fabric production jar contains NeoForge metadata'
  fi
  grep -Fxq 'META-INF/neoforge.mods.toml' <<< "$neoforge_listing" \
    || fail 'NeoForge production jar is missing neoforge.mods.toml'
  if grep -Fxq 'fabric.mod.json' <<< "$neoforge_listing"; then
    fail 'NeoForge production jar contains Fabric metadata'
  fi
  for listing_name in fabric_listing neoforge_listing; do
    listing=${!listing_name}
    grep -Fxq 'fakeaiplayer.mixins.json' <<< "$listing" \
      || fail "$listing_name is missing the production Mixin config"
    grep -Fxq 'assets/fakeaiplayer/lang/en_us.json' <<< "$listing" \
      || fail "$listing_name is missing shared English resources"
    grep -Fxq 'assets/fakeaiplayer/lang/zh_cn.json' <<< "$listing" \
      || fail "$listing_name is missing shared Chinese resources"
  done

  [[ -d common/build/classes/java/main ]] \
    || fail 'common compiled classes are unavailable for artifact parity inspection'
  while IFS= read -r -d '' class_file; do
    relative=${class_file#common/build/classes/java/main/}
    grep -Fxq "$relative" <<< "$fabric_listing" \
      || fail "Fabric production jar is missing shared class: $relative"
    grep -Fxq "$relative" <<< "$neoforge_listing" \
      || fail "NeoForge production jar is missing shared class: $relative"
  done < <(find common/build/classes/java/main -type f -name '*.class' -print0)
fi

bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md \
  || fail 'generated capability matrix is stale or its pinned evidence is invalid'

printf '[ci-static] OK: source-set, loader parity, workflow, shell and artifact invariants hold\n'
