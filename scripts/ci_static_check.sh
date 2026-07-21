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
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotAiSubcommand.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/BotApiCredentialRegistry.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/BotAiConnectionService.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/LatestPerBotExecutor.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/chat/BotChatRouter.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/social/BotSocialCoordinator.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/client/credential/ClientCredentialStore.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/client/credential/ClientCredentialManager.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/client/screen/BotAiSetupScreen.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/network/BotAiSetupSessions.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/network/payload/SetBotAiCredentialC2S.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/network/payload/RestoreBotAiCredentialC2S.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/network/payload/OpenBotAiSetupS2C.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/network/payload/BotAiCredentialStatusS2C.java
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
  common/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/SharedGameTestFixture.java
  common/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/SharedP0P1GameTestScenarios.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/mixin/BlockItemPlacementMixin.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/task/tree/PlayerPlacedLogLedger.java
  fabric/src/gametest/resources/fabric.mod.json
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerDeterministicGameTests.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP0P1GameTests.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerHarnessTestMod.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotTestSubcommand.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotVerifySubcommand.java
  neoforge/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP0P1GameTests.java
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

for mixin_config in fabric/src/main/resources/fakeaiplayer.mixins.json \
    neoforge/src/main/resources/fakeaiplayer.mixins.json; do
  grep -Fq '"BlockItemPlacementMixin"' "$mixin_config" \
    || fail "$mixin_config does not record player BlockItem placements"
  if grep -Fq '"ServerPlayerGameModeMixin"' "$mixin_config"; then
    fail "$mixin_config reintroduces non-transactional provenance deletion"
  fi
done

[[ ! -e common/src/main/java/io/github/greytaiwolf/fakeaiplayer/mixin/ServerPlayerGameModeMixin.java ]] \
  || fail 'P1 must not delete monotonic placement evidence on block destruction'

ledger=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/task/tree/PlayerPlacedLogLedger.java
placement_mixin=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/mixin/BlockItemPlacementMixin.java
build_action=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/action/BuildAction.java
grep -Fq 'loadIntegrityTrusted = false' "$ledger" \
  || fail 'placement ledger does not latch failed load integrity'
grep -Fq '!sessionMarkerActive' "$ledger" \
  || fail 'placement ledger writes are not guarded by a trusted active-session marker'
grep -Fq 'player_placed_log_ledger_baseline_required' "$ledger" \
  || fail 'worlds without a placement baseline do not fail closed'
grep -Fq 'player_placed_log_ledger_unclean_session' "$ledger" \
  || fail 'unclean ledger sessions cannot fail closed across restart'
grep -Fq 'closeSessionMarkerAfterCleanFlush' "$ledger" \
  || fail 'placement ledger does not retain its session marker until a clean final flush'
grep -Fq 'onServerStopped' "$ledger" \
  || fail 'placement ledger can clear its session marker before the terminal stopped boundary'
if grep -Fq 'positions.remove' "$ledger"; then
  fail 'P1 placement evidence must remain monotonic until a transactional compactor exists'
fi
grep -Fq 'SERVER_STOPPED.register(FakeAiPlayer::onServerStopped)' \
    fabric/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/fabric/FakeAiPlayerFabric.java \
  || fail 'Fabric does not finalize placement provenance after world shutdown'
grep -Fq 'ServerStoppedEvent' \
    neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgeGameEvents.java \
  || fail 'NeoForge does not finalize placement provenance after world shutdown'
grep -Fq 'FakeAiPlayer.onServerStopped(event.getServer())' \
    neoforge/src/main/java/io/github/greytaiwolf/fakeaiplayer/platform/neoforge/NeoForgeGameEvents.java \
  || fail 'NeoForge stopped event is not wired to placement provenance finalization'
grep -Fq 'trust-empty-log-baseline' \
    common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotPersistSubcommand.java \
  || fail 'missing explicit administrator command for an audited empty log baseline'
grep -Fq '@At("HEAD"), cancellable = true' "$placement_mixin" \
  || fail 'BlockItem log placement is not cancellable before an untracked world mutation'
grep -Fq 'allowsTrackedPlacement(state)' "$placement_mixin" \
  || fail 'BlockItem placement does not fail closed when provenance is unavailable'
grep -Fq 'allowsTrackedPlacement(placementState)' "$build_action" \
  || fail 'direct Bot placement does not fail closed when provenance is unavailable'

pose_planner=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/InteractionPosePlanner.java
path_executor=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/PathExecutor.java
tree_session=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/task/tree/TreeFellingSession.java
shared_scenarios=common/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/SharedP0P1GameTestScenarios.java
grep -Fq 'Predicate<BlockPos> pathPositionConstraint' "$pose_planner" \
  || fail 'interaction pose A* is missing the skill-owned feet constraint'
grep -Fq 'Set<BlockPos> persistentExclusions' "$path_executor" \
  || fail 'path executor cannot retain skill exclusions across replans'
grep -Fq 'positionConstraint' "$path_executor" \
  || fail 'path executor cannot retain skill constraints across replans'
grep -Fq 'feet -> hasIndependentSupport(bot, feet)' "$tree_session" \
  || fail 'tree felling does not apply independent support to the full route lifecycle'
grep -Fq 'BuildAction.placeBlock(' "$shared_scenarios" \
  || fail 'shared GameTest does not exercise the real BlockItem placement chain'
grep -Fq 'isKnownPlayerPlaced' "$shared_scenarios" \
  || fail 'shared GameTest does not verify loader placement provenance'

grep -Fq 'gametest {' neoforge/build.gradle \
  || fail 'NeoForge GameTest must use an isolated source set'
grep -Fq "java.srcDir rootProject.file('common/src/gametest/java')" neoforge/build.gradle \
  || fail 'NeoForge GameTest does not compile the shared scenarios'
grep -Fq 'addModdingDependenciesTo sourceSets.gametest' neoforge/build.gradle \
  || fail 'NeoForge GameTest source set is missing modding dependencies'
grep -Fq "type = 'gameTestServer'" neoforge/build.gradle \
  || fail 'NeoForge is missing the official GameTest server run type'
grep -Fq "systemProperty 'neoforge.enabledGameTestNamespaces', mod_id" neoforge/build.gradle \
  || fail 'NeoForge GameTest namespace is not pinned to the production mod id'
grep -Fq "tasks.named('runGameTestServer')" neoforge/build.gradle \
  || fail 'NeoForge GameTest server task is not prepared for headless execution'
grep -Fq 'player_placed_logs.json' fabric/build.gradle \
  || fail 'Fabric isolated GameTest world is missing its explicit trusted log baseline'
grep -Fq 'player_placed_logs.json' neoforge/build.gradle \
  || fail 'NeoForge isolated GameTest world is missing its explicit trusted log baseline'

fabric_shared_tests=fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP0P1GameTests.java
neoforge_shared_tests=neoforge/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP0P1GameTests.java
grep -Fq '@GameTestHolder(FakeAiPlayer.MOD_ID)' "$neoforge_shared_tests" \
  || fail 'NeoForge shared scenarios are missing @GameTestHolder with the production namespace'
grep -Fq '@PrefixGameTestTemplate(false)' "$neoforge_shared_tests" \
  || fail 'NeoForge shared template id would be rewritten by the holder prefix'

fabric_batches="$(grep -oE 'batch = "[^"]+"' "$fabric_shared_tests" | sort)"
neoforge_batches="$(grep -oE 'batch = "[^"]+"' "$neoforge_shared_tests" | sort)"
[[ -n "$fabric_batches" ]] || fail 'Fabric shared GameTest wrapper has no scenario ids'
[[ "$fabric_batches" == "$neoforge_batches" ]] \
  || fail 'Fabric and NeoForge shared GameTest scenario ids differ'
fabric_contracts="$(grep -oE 'batch = "[^"]+", timeoutTicks = [0-9]+' \
    "$fabric_shared_tests" | sort)"
neoforge_contracts="$(grep -oE 'batch = "[^"]+", timeoutTicks = [0-9]+' \
    "$neoforge_shared_tests" | sort)"
[[ "$fabric_contracts" == "$neoforge_contracts" ]] \
  || fail 'Fabric and NeoForge shared GameTest ids/timeouts differ'
fabric_scenarios="$(grep -oE 'SharedP0P1GameTestScenarios\.[A-Za-z0-9_]+' \
    "$fabric_shared_tests" | sort)"
neoforge_scenarios="$(grep -oE 'SharedP0P1GameTestScenarios\.[A-Za-z0-9_]+' \
    "$neoforge_shared_tests" | sort)"
[[ "$fabric_scenarios" == "$neoforge_scenarios" ]] \
  || fail 'Fabric and NeoForge wrappers do not delegate to the same shared scenarios'
for shared_wrapper in "$fabric_shared_tests" "$neoforge_shared_tests"; do
  if grep -Eq 'attempts[[:space:]]*=|requiredSuccesses[[:space:]]*=' "$shared_wrapper"; then
    fail "$shared_wrapper enables retries without isolating the monotonic provenance ledger"
  fi
done

[[ ! -e common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotTestSubcommand.java ]] \
  || fail 'test command leaked into production source set'
[[ ! -e common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotVerifySubcommand.java ]] \
  || fail 'verify command leaked into production source set'
if find common/src/main fabric/src/main neoforge/src/main -type f \
    \( -iname '*gametest*.java' -o -path '*/gametest/*' \) -print -quit | grep -q .; then
  fail 'GameTest implementation leaked into the production source set'
fi
if grep -RqE 'AIBot(Test|Verify)Subcommand' \
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
shared_preview_renderer=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/building/preview/client/BuildingPreviewWorldRenderer.java
preview_service=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/building/preview/BuildingPreviewService.java
preview_palette=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/building/preview/client/ResolvedPreviewPalette.java
ghost_block_renderer=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/building/preview/client/GhostBlockRenderer.java
support_contract=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/building/plan/BuildingSupportContract.java
blueprint_adapter=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/building/plan/BuildingPlanBlueprintAdapter.java
build_task=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/task/BuildTask.java
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

# Protocol v4 retains the server-authoritative vanilla Menu and adds the per-Bot credential
# handshake. Check the inventory edge here; the credential-specific invariants follow below.
grep -Fq 'PROTOCOL_VERSION = "4"' "$neoforge_payloads" \
  || fail 'NeoForge protocol version was not bumped to 4'
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

# Per-Bot credentials are client-persisted plaintext by explicit product choice, but activation is
# server-authoritative and two-phase: stage -> no-tool probe -> commit/reject. Keep the safety and
# dual-loader edges explicit in addition to the generic payload parity catalog above.
credential_registry=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/BotApiCredentialRegistry.java
connection_service=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/BotAiConnectionService.java
server_networking=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/network/AIBotServerNetworking.java
setup_sessions=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/network/BotAiSetupSessions.java
client_store=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/client/credential/ClientCredentialStore.java
client_manager=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/client/credential/ClientCredentialManager.java
setup_screen=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/client/screen/BotAiSetupScreen.java
chat_router=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/chat/BotChatRouter.java
social_coordinator=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/social/BotSocialCoordinator.java

grep -Fq '.then(AIBotAiSubcommand.build())' \
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotCommand.java \
  || fail 'per-Bot AI credential commands are not attached to the production command tree'
for invariant in 'literal("setup")' 'literal("status")' 'literal("test")' 'literal("disconnect")'; do
  grep -Fq "$invariant" common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotAiSubcommand.java \
    || fail "per-Bot AI credential command is missing: $invariant"
done

credential_c2s=(SetBotAiCredentialC2S RestoreBotAiCredentialC2S)
credential_s2c=(OpenBotAiSetupS2C BotAiCredentialStatusS2C)
for payload in "${credential_c2s[@]}"; do
  grep -Fq "playC2S().register(${payload}.ID" "$fabric_payloads" \
    || fail "Fabric is missing credential C2S type registration: $payload"
  grep -Fq "registerGlobalReceiver(${payload}.ID" "$fabric_payloads" \
    || fail "Fabric is missing credential C2S receiver: $payload"
  grep -Fq "playToServer(${payload}.ID" "$neoforge_payloads" \
    || fail "NeoForge is missing credential C2S handler: $payload"
done
for payload in "${credential_s2c[@]}"; do
  grep -Fq "playS2C().register(${payload}.ID" "$fabric_payloads" \
    || fail "Fabric is missing credential S2C type registration: $payload"
  grep -Fq "registerGlobalReceiver(${payload}.ID" "$fabric_client" \
    || fail "Fabric is missing credential S2C client receiver: $payload"
  grep -Fq "playToClient(${payload}.ID" "$neoforge_payloads" \
    || fail "NeoForge is missing credential S2C client handler: $payload"
done
for handler in handleSetBotAiCredential handleRestoreBotAiCredential; do
  grep -Fq "$handler" "$fabric_payloads" \
    || fail "Fabric is missing credential server handler: $handler"
  grep -Fq "$handler" "$neoforge_payloads" \
    || fail "NeoForge is missing credential server handler: $handler"
done

for invariant in 'stageBotApiKey(' 'commitBotApiKey(' 'rejectBotApiKey('; do
  grep -Fq "$invariant" "$server_networking" \
    || fail "credential network flow is missing two-phase transition: $invariant"
done
if grep -Fq 'setBotApiKey(' "$server_networking"; then
  fail 'credential network flow activates an unverified key before the provider probe'
fi
for invariant in 'clientForProbe(' 'List.of()' 'This turn has no tools'; do
  grep -Fq "$invariant" "$connection_service" \
    || fail "credential probe is missing no-tool/staged invariant: $invariant"
done
for invariant in 'pendingApiKey' 'pendingGeneration' 'commit(UUID botId' 'reject(UUID botId'; do
  grep -Fq "$invariant" "$credential_registry" \
    || fail "credential registry is missing staged replacement invariant: $invariant"
done
for invariant in 'consume(' 'clearAll()'; do
  grep -Fq "$invariant" "$setup_sessions" \
    || fail "credential setup nonce lifecycle is missing: $invariant"
done

for payload in SetBotAiCredentialC2S RestoreBotAiCredentialC2S; do
  payload_file="$payload_dir/${payload}.java"
  grep -Fq 'apiKey=<redacted>' "$payload_file" \
    || fail "$payload does not redact its secret-bearing toString()"
done
for invariant in 'FILE_NAME = "fakeaiplayer-client.json"' 'OWNER_ONLY' 'ATOMIC_MOVE' 'applyOwnerOnlyPermissions'; do
  grep -Fq "$invariant" "$client_store" \
    || fail "client credential store is missing plaintext-file safety invariant: $invariant"
done
grep -Fq 'RestoreBotAiCredentialC2S' "$client_manager" \
  || fail 'saved client credentials are not restored on the matching connection scope'
for invariant in 'setFormatter(' 'GLFW.GLFW_KEY_C' 'GLFW.GLFW_KEY_X' \
    'payload.connected() && !pendingKey.isBlank()'; do
  grep -Fq "$invariant" "$setup_screen" \
    || fail "masked credential screen is missing safety invariant: $invariant"
done

grep -Fq 'BotChatRouter.INSTANCE.route' "$server_networking" \
  || fail 'bot output does not pass through the owner-only chat router'
for invariant in 'getPlayer(ownerId)' 'sendSystemMessage' '"[AI] <"'; do
  grep -Fq "$invariant" "$chat_router" \
    || fail "owner-only chat router is missing invariant: $invariant"
done
grep -Fq 'BotSocialRequest.withoutTools' "$social_coordinator" \
  || fail 'proactive social turns are not constrained to the no-tool request type'

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
# Ghost blocks use vanilla entityTranslucent and therefore must render while the Fabulous entity
# target/composite lifetime is active. Keep both loader adapters at their AFTER_ENTITIES boundary.
grep -Fq 'RenderLevelStageEvent.Stage.AFTER_ENTITIES' "$neoforge_renderer" \
  || fail 'NeoForge building preview renderer is not attached to the entity-translucent stage'
grep -Fq 'ClientPlayerNetworkEvent.LoggingOut' "$neoforge_renderer" \
  || fail 'NeoForge building preview client state is not cleared on logout'
for adapter in "$fabric_renderer" "$neoforge_renderer"; do
  grep -Fq 'BuildingPreviewWorldRenderer' "$adapter" \
    || fail "$adapter does not delegate to the shared building preview renderer"
done
for invariant in \
    MAX_RENDER_DISTANCE_SQUARED \
    BuildingPreviewClientState.INSTANCE.active \
    CLEAR_CONFLICT \
    PRESERVE_CONFLICT; do
  grep -Fq "$invariant" "$shared_preview_renderer" \
    || fail "$shared_preview_renderer is missing building preview renderer invariant: $invariant"
done
for invariant in \
    'MAX_CONFIRM_VALIDATION_CELLS_GLOBAL_TICK = 4_096' \
    'MAX_CONFIRM_VALIDATION_CELLS_PER_VIEWER_TICK = 1_024' \
    'CONFIRM_RETRY_COOLDOWN_TICKS = 100' \
    'tickConfirmations(server)' \
    'new ConfirmationValidation'; do
  grep -Fq "$invariant" "$preview_service" \
    || fail "$preview_service is missing paced confirmation invariant: $invariant"
done
grep -Fq 'BuildingSupportContract.requiresExternalSupport' "$preview_service" \
  || fail 'preview confirmation does not use the shared terrain-support contract'
grep -Fq 'BuildingSupportContract.requiresExternalSupport' "$blueprint_adapter" \
  || fail 'executor blueprint does not persist the shared terrain-support contract'
grep -Fq 'placement.requiresExternalSupport()' "$build_task" \
  || fail 'BuildTask does not recheck persisted external terrain support'
grep -Fq 'BlockStateResolver.resolve' "$preview_palette" \
  || fail "$preview_palette does not cache resolved preview block states"
grep -Fq 'ShapeRenderer.renderLineBox' "$ghost_block_renderer" \
  || fail "$ghost_block_renderer is missing conflict/fallback wireframes"
grep -Fq 'RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS)' "$ghost_block_renderer" \
  || fail "$ghost_block_renderer does not use the entity-translucent block-atlas render type"
for invariant in \
    'MAX_TRANSFER_CHUNKS_PER_TICK = 8' \
    'tickTransfers(server)' \
    'new BuildingPreviewTransfer' \
    'transfer.complete()'; do
  grep -Fq "$invariant" "$preview_service" \
    || fail "$preview_service is missing paced preview transfer invariant: $invariant"
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
  grep -Fq ':fabric:runGameTest' "$workflow" \
    || fail "$workflow does not execute the Fabric GameTest server"
  grep -Fq ':neoforge:runGameTestServer' "$workflow" \
    || fail "$workflow does not execute the NeoForge GameTest server"
  grep -Fq 'neoforge/build/run/gameTest/logs/**' "$workflow" \
    || fail "$workflow does not upload NeoForge GameTest logs"
  grep -Fq 'neoforge/build/run/gameTest/crash-reports/**' "$workflow" \
    || fail "$workflow does not upload NeoForge GameTest crash reports"
  if grep -Fq 'scripts/food_test.sh' "$workflow"; then
    fail "$workflow still invokes the legacy shared-run wrapper"
  fi
done

for workflow in .github/workflows/ci.yml .github/workflows/nightly.yml; do
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
    if grep -Eq 'io/github/greytaiwolf/fakeaiplayer/(gametest/|command/AIBot(Test|Verify)Subcommand)|data/fakeaiplayer/structure/p0_arena\.nbt' \
        <<< "$jar_listing"; then
      fail "verification harness or test structure leaked into jar: $jar_file"
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
