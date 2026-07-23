#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"

fail() {
  printf '[ci-static] ERROR: %s\n' "$1" >&2
  exit 1
}

# These numbers describe the P3 release candidate. Any intentional test addition or removal must
# update the baseline in the same reviewed change; an accidental loss of discovery must fail CI.
readonly expected_junit_classes=95
readonly expected_junit_tests=480
readonly expected_fabric_gametests=55
readonly expected_neoforge_gametests=31
readonly expected_p3_shared_gametests=6

# Production artifacts must never contain loader test entrypoints or the generated GameTest
# arenas. Keep this expression centralized so the ordinary (pre-build) static pass can exercise
# the same guard that the post-build jar inspection uses.
readonly verification_jar_leak_regex='io/github/greytaiwolf/fakeaiplayer/(gametest/|command/AIBot(Test|Verify)Subcommand)|data/fakeaiplayer/structure/(p0_arena|p3_mission_arena)\.nbt'
for generated_test_asset in \
    'data/fakeaiplayer/structure/p0_arena.nbt' \
    'data/fakeaiplayer/structure/p3_mission_arena.nbt'; do
  if ! grep -Eq "$verification_jar_leak_regex" <<< "$generated_test_asset"; then
    fail "production-jar leak guard misses generated test asset: $generated_test_asset"
  fi
done
if grep -Eq "$verification_jar_leak_regex" \
    <<< 'data/fakeaiplayer/structure/production_structure.nbt'; then
  fail 'production-jar leak guard overmatches non-test structures'
fi

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
  common/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/SharedP2NavigationGameTestScenarios.java
  common/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/SharedP3MissionGameTestScenarios.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/NavGoal.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/NavigationHandle.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/MultiGoalAStarPathfinder.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/mixin/BlockItemPlacementMixin.java
  common/src/main/java/io/github/greytaiwolf/fakeaiplayer/task/tree/PlayerPlacedLogLedger.java
  fabric/src/gametest/resources/fabric.mod.json
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerDeterministicGameTests.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP0P1GameTests.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP2GameTests.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP3GameTests.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerHarnessTestMod.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotTestSubcommand.java
  fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotVerifySubcommand.java
  neoforge/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP0P1GameTests.java
  neoforge/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP2GameTests.java
  neoforge/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP3GameTests.java
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

actual_junit_classes="$(find common/src/test/java -type f -name '*Test.java' | wc -l \
    | tr -d '[:space:]')"
actual_junit_tests="$(grep -R -h --include='*Test.java' \
    -E '^[[:space:]]*@Test([[:space:]]|$)' common/src/test/java | wc -l \
    | tr -d '[:space:]')"
actual_fabric_gametests="$(grep -R -h --include='*.java' \
    -E '^[[:space:]]*@GameTest([[:space:](]|$)' fabric/src/gametest/java | wc -l \
    | tr -d '[:space:]')"
actual_neoforge_gametests="$(grep -R -h --include='*.java' \
    -E '^[[:space:]]*@GameTest([[:space:](]|$)' neoforge/src/gametest/java | wc -l \
    | tr -d '[:space:]')"
[[ "$actual_junit_classes" == "$expected_junit_classes" ]] \
  || fail "JUnit class baseline changed: expected $expected_junit_classes, found $actual_junit_classes"
[[ "$actual_junit_tests" == "$expected_junit_tests" ]] \
  || fail "JUnit @Test baseline changed: expected $expected_junit_tests, found $actual_junit_tests"
[[ "$actual_fabric_gametests" == "$expected_fabric_gametests" ]] \
  || fail "Fabric GameTest baseline changed: expected $expected_fabric_gametests, found $actual_fabric_gametests"
[[ "$actual_neoforge_gametests" == "$expected_neoforge_gametests" ]] \
  || fail "NeoForge GameTest baseline changed: expected $expected_neoforge_gametests, found $actual_neoforge_gametests"

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
grep -Fq 'FakeAiPlayerSharedP2GameTests' fabric/src/gametest/resources/fabric.mod.json \
  || fail 'P2 shared GameTest entrypoint is not registered'

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

p2_scenarios=common/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/SharedP2NavigationGameTestScenarios.java
fabric_p2_tests=fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP2GameTests.java
neoforge_p2_tests=neoforge/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP2GameTests.java
grep -Fq '@GameTestHolder(FakeAiPlayer.MOD_ID)' "$neoforge_p2_tests" \
  || fail 'NeoForge P2 scenarios are missing @GameTestHolder'
grep -Fq '@PrefixGameTestTemplate(false)' "$neoforge_p2_tests" \
  || fail 'NeoForge P2 template id would be rewritten by the holder prefix'
fabric_p2_contracts="$(grep -oE 'batch = "[^"]+", timeoutTicks = [0-9]+' \
    "$fabric_p2_tests" | sort)"
neoforge_p2_contracts="$(grep -oE 'batch = "[^"]+", timeoutTicks = [0-9]+' \
    "$neoforge_p2_tests" | sort)"
[[ -n "$fabric_p2_contracts" && "$fabric_p2_contracts" == "$neoforge_p2_contracts" ]] \
  || fail 'Fabric and NeoForge P2 GameTest ids/timeouts differ'
fabric_p2_scenarios="$(grep -oE 'SharedP2NavigationGameTestScenarios\.[A-Za-z0-9_]+' \
    "$fabric_p2_tests" | sort)"
neoforge_p2_scenarios="$(grep -oE 'SharedP2NavigationGameTestScenarios\.[A-Za-z0-9_]+' \
    "$neoforge_p2_tests" | sort)"
[[ "$fabric_p2_scenarios" == "$neoforge_p2_scenarios" ]] \
  || fail 'Fabric and NeoForge P2 wrappers do not delegate to the same scenarios'
shared_p2_scenarios="$(grep -oE 'public static void [A-Za-z0-9_]+' \
    "$p2_scenarios" | awk '{print $4}' | sort)"
fabric_p2_delegates="$(printf '%s\n' "$fabric_p2_scenarios" \
    | sed 's/^SharedP2NavigationGameTestScenarios\.//' | sort)"
neoforge_p2_delegates="$(printf '%s\n' "$neoforge_p2_scenarios" \
    | sed 's/^SharedP2NavigationGameTestScenarios\.//' | sort)"
[[ "$shared_p2_scenarios" == "$fabric_p2_delegates"
    && "$shared_p2_scenarios" == "$neoforge_p2_delegates" ]] \
  || fail 'A shared P2 scenario is missing or duplicated in a loader wrapper'
grep -Fq 'implements FabricGameTest' "$fabric_p2_tests" \
  || fail 'Fabric P2 wrapper is not registered through FabricGameTest'
for shared_wrapper in "$fabric_p2_tests" "$neoforge_p2_tests"; do
  duplicate_batch="$(grep -oE 'batch = "[^"]+"' "$shared_wrapper" \
      | sort | uniq -d | head -1)"
  [[ -z "$duplicate_batch" ]] \
    || fail "$shared_wrapper contains duplicate P2 batch id $duplicate_batch"
done
for shared_wrapper in "$fabric_p2_tests" "$neoforge_p2_tests"; do
  if grep -Eq 'attempts[[:space:]]*=|requiredSuccesses[[:space:]]*=' "$shared_wrapper"; then
    fail "$shared_wrapper enables retries without isolated P2 fixtures"
  fi
done
grep -Fq 'frontiersStarted() == 1' "$p2_scenarios" \
  || fail 'P2 single-search scenario does not assert a request-owned frontier count'

p3_scenarios=common/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/SharedP3MissionGameTestScenarios.java
fabric_p3_tests=fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP3GameTests.java
neoforge_p3_tests=neoforge/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSharedP3GameTests.java
for invariant in \
    'ResumeSideEffectTask mission = new ResumeSideEffectTask()' \
    'mission.resumeCalls() == 0' \
    'mission.resumeCalls() == 1' \
    'TaskManager.INSTANCE.enterRuntimeRecoveryMode("p3_recovery_gate_fixture")' \
    'Routine REFLEX bypassed read-only recovery' \
    'Immediate SAFETY work was blocked by read-only recovery'; do
  grep -Fq "$invariant" "$p3_scenarios" \
    || fail "Shared P3 interruption scenario is missing onResume isolation evidence: $invariant"
done
grep -Fq '"io.github.greytaiwolf.fakeaiplayer.gametest.FakeAiPlayerSharedP3GameTests"' \
    fabric/src/gametest/resources/fabric.mod.json \
  || fail 'Fabric P3 GameTest entrypoint is not registered'
grep -Fq 'implements FabricGameTest' "$fabric_p3_tests" \
  || fail 'Fabric P3 wrapper is not registered through FabricGameTest'
grep -Fq '@GameTestHolder(FakeAiPlayer.MOD_ID)' "$neoforge_p3_tests" \
  || fail 'NeoForge P3 scenarios are missing @GameTestHolder with the production namespace'
grep -Fq '@PrefixGameTestTemplate(false)' "$neoforge_p3_tests" \
  || fail 'NeoForge P3 template ids would be rewritten by the holder prefix'
fabric_p3_contracts="$(grep -oE 'batch = "[^"]+", timeoutTicks = [0-9]+' \
    "$fabric_p3_tests" | sort)"
neoforge_p3_contracts="$(grep -oE 'batch = "[^"]+", timeoutTicks = [0-9]+' \
    "$neoforge_p3_tests" | sort)"
[[ -n "$fabric_p3_contracts" && "$fabric_p3_contracts" == "$neoforge_p3_contracts" ]] \
  || fail 'Fabric and NeoForge P3 GameTest ids/timeouts differ'
fabric_p3_scenarios="$(grep -oE 'SharedP3MissionGameTestScenarios\.[A-Za-z0-9_]+' \
    "$fabric_p3_tests" | sort)"
neoforge_p3_scenarios="$(grep -oE 'SharedP3MissionGameTestScenarios\.[A-Za-z0-9_]+' \
    "$neoforge_p3_tests" | sort)"
[[ "$fabric_p3_scenarios" == "$neoforge_p3_scenarios" ]] \
  || fail 'Fabric and NeoForge P3 wrappers do not delegate to the same shared scenarios'
shared_p3_scenarios="$(grep -oE 'public static void [A-Za-z0-9_]+' \
    "$p3_scenarios" | awk '{print $4}' | sort)"
fabric_p3_delegates="$(printf '%s\n' "$fabric_p3_scenarios" \
    | sed 's/^SharedP3MissionGameTestScenarios\.//' | sort)"
[[ "$shared_p3_scenarios" == "$fabric_p3_delegates" ]] \
  || fail 'A shared P3 scenario is missing or duplicated in a loader wrapper'
actual_p3_shared_gametests="$(printf '%s\n' "$fabric_p3_delegates" \
    | sed '/^[[:space:]]*$/d' | wc -l | tr -d '[:space:]')"
[[ "$actual_p3_shared_gametests" == "$expected_p3_shared_gametests" ]] \
  || fail "P3 shared GameTest baseline changed: expected $expected_p3_shared_gametests, found $actual_p3_shared_gametests"
for shared_wrapper in "$fabric_p3_tests" "$neoforge_p3_tests"; do
  duplicate_batch="$(grep -oE 'batch = "[^"]+"' "$shared_wrapper" \
      | sort | uniq -d | head -1)"
  [[ -z "$duplicate_batch" ]] \
    || fail "$shared_wrapper contains duplicate P3 batch id $duplicate_batch"
done
grep -Fq 'batch = "fakeaiplayer_shared_p3_golden_chain", timeoutTicks = 6000' \
    "$fabric_p3_tests" \
  || fail 'P3 does not run the zero-inventory survival-to-iron golden chain on both loaders'
grep -Fq "[name: 'p3_mission_arena', size: [21, 21, 21]]" common/build.gradle \
  || fail 'P3 golden chain does not have a bounded tall GameTest template'
grep -Fq 'template = GOLDEN_ARENA, batch = "fakeaiplayer_shared_p3_golden_chain"' \
    "$fabric_p3_tests" \
  || fail 'P3 golden chain is not registered against its dedicated tall template'
grep -Fq 'template = GOLDEN_ARENA, batch = "fakeaiplayer_shared_p3_golden_chain"' \
    "$neoforge_p3_tests" \
  || fail 'NeoForge P3 golden chain is not registered against its dedicated tall template'

restart_harness=fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/AIBotRestartHarnessCommand.java
for invariant in \
    'MissionCheckpointCodec.RUNTIME_BUDGET_V3' \
    'CraftTask.class::isInstance' \
    'recoveriesConsumed() < 1' \
    'recoveryBudgetExact' \
    'runtimeStateExact' \
    'checkpointMetadataExact' \
    'recovery().equals' \
    'progress().equals' \
    'contextFingerprint().equals' \
    'replanAfterInterrupt()' \
    'cursor().equals' \
    'policyExact' \
    'planRevisionValid' \
    'planProvenanceValid' \
    'bound_plan_fingerprint' \
    'bound_intent_fingerprint' \
    'bound_context_fingerprint' \
    'mission_spec_binding' \
    'queue_spec_binding'; do
  grep -Fq "$invariant" "$restart_harness" \
    || fail "two-JVM restart harness is missing nonzero recovery invariant: $invariant"
done
for legacy_runtime_key in checkpoint_runtime_budget_v1 checkpoint_runtime_budget_v2; do
  if grep -Fq "$legacy_runtime_key" "$restart_harness"; then
    fail "two-JVM restart harness still persists read-only payload $legacy_runtime_key"
  fi
done
grep -Fq 'checkpoint_runtime_budget_v3' "$restart_harness" \
  || fail 'two-JVM restart harness does not preserve the V3 runtime payload'

checkpoint_codec=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/persist/MissionCheckpointCodec.java
for invariant in \
    'CURRENT_VERSION = 3' \
    'RUNTIME_BUDGET_V1 = "runtime_budget_v1"' \
    'RUNTIME_BUDGET_V2 = "runtime_budget_v2"' \
    'RUNTIME_BUDGET_V3 = "runtime_budget_v3"' \
    'multiple_runtime_budget_payloads' \
    'mission_checkpoint_plan_binding_missing' \
    'runtime_intent_fingerprint_invalid' \
    'runtime_context_fingerprint_invalid' \
    'V3_PAYLOAD_PARTS = 20' \
    'CursorCheckpoint cursor' \
    'boolean replanAfterInterrupt' \
    'public boolean bound()' \
    'public boolean current()'; do
  grep -Fq "$invariant" "$checkpoint_codec" \
    || fail "Mission checkpoint V3 contract is missing invariant: $invariant"
done
cursor_codec=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/persist/CursorCheckpointCodec.java
for invariant in \
    'CursorCheckpoint.CURRENT_VERSION' \
    'cursor_checkpoint_checksum_mismatch' \
    'checkpointReached' \
    'activationCounts'; do
  grep -Fq "$invariant" "$cursor_codec" \
    || fail "Cursor checkpoint contract is missing invariant: $invariant"
done
goal_executor=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/goal/GoalExecutor.java
for invariant in \
    'mission_intent_binding_mismatch' \
    'MissionPlan.intentFingerprint(incomingGoalSpec)' \
    'incrementMissionTicks(plan.elapsedMissionTicks)' \
    'mission_checkpoint_downgrade_detected' \
    'spec.bindingValid()' \
    'spec.legacyUnboundShape()' \
    'mission_plan_revision_exhausted' \
    'active.planCursor.checkpoint()' \
    'contextFingerprint(active)' \
    'runtime.current() ? runtime.cursor() : null'; do
  grep -Fq "$invariant" "$goal_executor" \
    || fail "GoalExecutor P3 restore boundary is missing invariant: $invariant"
done
for invariant in \
    'plan.planCursor.tryCompleteSkill(' \
    'if (!completion.accepted())' \
    'reconcileCursorActivation(server, bot, plan, advancedCursor' \
    'verifyStableCheckpointBoundary(active)' \
    'latchPendingInterruptionForPersistence(bot, active)' \
    'TaskManager.INSTANCE.hasMissionInterruption(bot, active.missionId)' \
    'canReuseUniqueRecompiledReservation(' \
    'if (plan.replanAfterInterrupt' \
    'TaskManager.INSTANCE.hasNavigationSafetyLease(bot)' \
    'missionCurrentlyPaused && !resumed' \
    '!plan.restoredAdmissionPending' \
    'restoredAdmissionReady(bot)' \
    'runtime_recovery_goal_rejected' \
    'boolean restoredInterruptAwaitingReplan'; do
  grep -Fq "$invariant" "$goal_executor" \
    || fail "GoalExecutor P3 runtime bridge is missing invariant: $invariant"
done
task_manager=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/task/TaskManager.java
preview_body="$(sed -n '/private MissionArbiter.Decision previewDecision(/,/private TaskOrigin currentOwnerOrigin(/p' \
    "$task_manager")"
grep -Fq 'missionDimensionFailure(' <<< "$preview_body" \
  || fail 'Task assignment preview does not reject a bound Mission before start in the wrong dimension'
dimension_order="$(grep -n -m1 'missionDimensionFailure(' <<< "$preview_body" | cut -d: -f1)"
navigation_order="$(grep -n -m1 'navigationSafetyLeases.get' <<< "$preview_body" | cut -d: -f1)"
[[ -n "$dimension_order" && -n "$navigation_order" && "$dimension_order" -lt "$navigation_order" ]] \
  || fail 'Mission dimension admission must run before other Task assignment gates'
grep -Fq 'origin.kind() == TaskOrigin.Kind.MISSION' "$task_manager" \
  || fail 'Mission interruption quarantine can freeze a non-Mission compatibility origin'
for invariant in \
    'runtimeRecoveryMode' \
    'runtimeRecoveryGateBlocks(' \
    'runtimeRecoveryLockBypass(origin)' \
    'runtime_recovery_lock_tick_skipped'; do
  grep -Fq "$invariant" "$task_manager" \
    || fail "Partial-restore execution lock is missing invariant: $invariant"
done
recovery_bypass_body="$(sed -n '/static boolean runtimeRecoveryLockBypass(/,/static boolean runtimeRecoveryGateBlocks(/p' \
    "$task_manager")"
grep -Fq 'return origin != null && origin.safety();' <<< "$recovery_bypass_body" \
  || fail 'Read-only recovery bypass is broader than immediate SAFETY work'
if grep -Fq 'TaskOrigin.Kind.REFLEX' <<< "$recovery_bypass_body"; then
  fail 'Routine REFLEX work can mutate world/inventory during read-only recovery'
fi
idle_coordinator=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/coordination/IdleCoordinator.java
grep -Fq 'TaskManager.INSTANCE.hasRuntimeRecoveryLock(bot)' "$idle_coordinator" \
  || fail 'Idle ambient work can bypass the session-wide recovery gate'
bot_tick_coordinator=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/task/BotTickCoordinator.java
grep -Fq 'runtime_recovery_read_only' "$bot_tick_coordinator" \
  || fail 'Action-only ambient work is not stopped by the recovery coordinator gate'
action_dispatcher=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/ActionDispatcher.java
for invariant in \
    'RECOVERY_READ_ONLY_TOOLS' \
    'TaskManager.INSTANCE.hasRuntimeRecoveryLock(bot)' \
    'blocked: runtime_recovery_read_only'; do
  grep -Fq "$invariant" "$action_dispatcher" \
    || fail "LLM tools can bypass read-only recovery: $invariant"
done
brain_coordinator=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/brain/BrainCoordinator.java
for invariant in \
    'runtime_recovery_brain_request_rejected' \
    'TaskManager.INSTANCE.hasRuntimeRecoveryLock(bot)'; do
  grep -Fq "$invariant" "$brain_coordinator" \
    || fail "Brain decision lifecycle can bypass read-only recovery: $invariant"
done
brain_admission_body="$(sed -n '/public boolean handleMessage(/,/ensureConfigured();/p' \
    "$brain_coordinator")"
grep -Fq 'return false;' <<< "$brain_admission_body" \
  || fail 'A rejected recovery Brain request can still be reported as queued'
intent_controller=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/runtime/IntentController.java
for invariant in \
    'rejectRecoveryMutation(bot, origin, "cancel_current")' \
    'rejectRecoveryMutation(bot, origin, "cancel_all")' \
    'rejectRecoveryMutation(bot, origin, "replace")' \
    'rejectRecoveryMutation(bot, origin, "pause")' \
    'rejectRecoveryMutation(bot, origin, "resume")' \
    'origin != ControlOrigin.SYSTEM'; do
  grep -Fq "$invariant" "$intent_controller" \
    || fail "User/LLM control can clear recovery isolation: $invariant"
done
inventory_sessions=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/inventory/BotInventorySessionManager.java
grep -Fq 'TaskManager.INSTANCE.hasRuntimeRecoveryLock(bot)' "$inventory_sessions" \
  || fail 'Bot inventory can be edited while its source snapshot is preserved read-only'
ai_player_manager=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/manager/AIPlayerManager.java
grep -Fq 'TaskManager.INSTANCE.runtimeRecoveryModeActive()' "$ai_player_manager" \
  || fail 'A new Bot can be spawned after the runtime entered recovery mode'
bot_command=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotCommand.java
despawn_command_body="$(sed -n '/private static int despawn(/,/private static int list(/p' \
    "$bot_command")"
despawn_gate_order="$(grep -n -m1 'hasRuntimeRecoveryLock(bot.get())' \
    <<< "$despawn_command_body" | cut -d: -f1)"
despawn_mutation_order="$(grep -n -m1 'AIPlayerManager.INSTANCE.despawn' \
    <<< "$despawn_command_body" | cut -d: -f1)"
[[ -n "$despawn_gate_order" && -n "$despawn_mutation_order" \
    && "$despawn_gate_order" -lt "$despawn_mutation_order" ]] \
  || fail 'Bot deletion can mutate recovery state and falsely report a persistent delete'
fabric_safety_tests=fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/FakeAiPlayerSafetyControlGameTests.java
for invariant in \
    'read-only recovery allowed a Bot inventory session' \
    'read-only recovery allowed a newly spawned Bot'; do
  grep -Fq "$invariant" "$fabric_safety_tests" \
    || fail "Fabric recovery isolation behavior is missing: $invariant"
done
grep -Fq 'SmeltTask.hasUsableFurnaceSource(session.runtimeBot())' \
    common/src/main/java/io/github/greytaiwolf/fakeaiplayer/goal/LegacySkillVerifier.java \
  || fail 'Mission furnace preflight is narrower than the SmeltTask discovery envelope'
submit_body="$(sed -n '/private boolean submit(/,/public boolean tickBot(/p' "$goal_executor")"
restore_cancel_admission_order="$(grep -n -m1 'restoredCancellationRequired(' \
    <<< "$submit_body" | cut -d: -f1)"
build_admission_order="$(grep -n -m1 'BlueprintSchema verifiedBuildBlueprint' \
    <<< "$submit_body" | cut -d: -f1)"
initial_evaluation_order="$(grep -n -m1 'GoalEvaluation initialEvaluation' \
    <<< "$submit_body" | cut -d: -f1)"
[[ -n "$restore_cancel_admission_order" && -n "$build_admission_order" \
    && -n "$initial_evaluation_order" \
    && "$restore_cancel_admission_order" -lt "$build_admission_order" \
    && "$restore_cancel_admission_order" -lt "$initial_evaluation_order" ]] \
  || fail 'A restored CANCEL action must terminate before build admission or world evaluation'
tick_body="$(sed -n '/public boolean tickBot(/,/public boolean hasActivePlan(/p' "$goal_executor")"
budget_order="$(grep -n -m1 'missionTimeBudgetExhausted(' <<< "$tick_body" | cut -d: -f1)"
reconcile_order="$(grep -n -m1 'reconcileCursorActivation(server, bot, plan, advancedCursor' \
    <<< "$tick_body" | cut -d: -f1)"
[[ -n "$budget_order" && -n "$reconcile_order" && "$budget_order" -lt "$reconcile_order" ]] \
  || fail 'Mission time budget must fail before a Timeout fallback can start'
interruption_peek_order="$(grep -n -m1 'hasMissionInterruption(bot, plan.missionId)' \
    <<< "$tick_body" | cut -d: -f1)"
interruption_consume_order="$(grep -n -m1 'consumeMissionInterruption(bot, plan.missionId)' \
    <<< "$tick_body" | cut -d: -f1)"
[[ -n "$interruption_peek_order" && -n "$interruption_consume_order" \
    && "$interruption_peek_order" -lt "$interruption_consume_order" ]] \
  || fail 'Mission interruption must be projected durably before the transient latch is consumed'
restored_cancel_order="$(grep -n -m1 'mission_policy_cancel_after_restored_interrupt' \
    <<< "$tick_body" | cut -d: -f1)"
dimension_gate_order="$(grep -n -m1 'mission_bound_dimension_changed' \
    <<< "$tick_body" | cut -d: -f1)"
user_pause_order="$(grep -n -m1 'TaskManager.INSTANCE.isUserPaused(bot)' \
    <<< "$tick_body" | cut -d: -f1)"
[[ -n "$restored_cancel_order" && -n "$user_pause_order" \
    && "$restored_cancel_order" -lt "$user_pause_order" ]] \
  || fail 'A durable CANCEL_ON_INTERRUPT action must terminate before USER pause can short-circuit restore'
[[ -n "$restored_cancel_order" && -n "$dimension_gate_order" \
    && "$restored_cancel_order" -lt "$dimension_gate_order" ]] \
  || fail 'A durable CANCEL_ON_INTERRUPT action must terminate before dimension mismatch classification'
task_tick_body="$(sed -n '/public void tickAll(/,/static String missionDimensionFailure(/p' "$task_manager")"
quarantine_order="$(grep -n -m1 'missionTickQuarantined(' <<< "$task_tick_body" | cut -d: -f1)"
dimension_tick_order="$(grep -n -m1 'missionDimensionFailure(' <<< "$task_tick_body" | cut -d: -f1)"
[[ -n "$quarantine_order" && -n "$dimension_tick_order" \
    && "$quarantine_order" -lt "$dimension_tick_order" ]] \
  || fail 'A resumed Mission Task must remain quarantined until its interruption policy is consumed'
resume_top_body="$(sed -n '/private boolean resumeTop(AIPlayerEntity bot,/,/public boolean pauseUserIntent(/p' \
    "$task_manager")"
resume_quarantine_order="$(grep -n -m1 'missionTickQuarantined(' \
    <<< "$resume_top_body" | cut -d: -f1)"
task_resume_order="$(grep -n -m1 'task.resume(bot)' <<< "$resume_top_body" | cut -d: -f1)"
[[ -n "$resume_quarantine_order" && -n "$task_resume_order" \
    && "$resume_quarantine_order" -lt "$task_resume_order" ]] \
  || fail 'Mission interruption quarantine must run before any Task onResume side effect'
grep -Fq 'resumeMissionAfterInterruption(' "$goal_executor" \
  || fail 'GoalExecutor has no explicit RESUME policy handoff for a quarantined Mission'
nav_safety=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/task/NavSafetyNet.java
emergency_release_body="$(sed -n '/if (emergencyTeleportToAir(/,/^[[:space:]]*}/p' "$nav_safety")"
grep -Fq 'return false;' <<< "$emergency_release_body" \
  || fail 'Emergency water rescue must hand the same coordinator tick back to GoalExecutor'
danger_watcher=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/task/DangerWatcher.java
grep -Fq 'return TaskManager.INSTANCE.resumeSafetyPause(bot);' "$danger_watcher" \
  || fail 'A refused automatic resume must not starve GoalExecutor policy handoff'
restore_runtime_body="$(sed -n '/public boolean restoreRuntime(/,/private static Map<String, String> checkpoint(/p' \
    "$goal_executor")"
grep -Fq 'return fullyAccounted;' <<< "$restore_runtime_body" \
  || fail 'Mission restore accounting body was not extracted or does not return its outcome'
if grep -Fq 'advanceQueue(bot)' <<< "$restore_runtime_body"; then
  fail 'Restored queued work must wait for the first safety-ordered Bot tick'
fi
authenticated_cancel_order="$(grep -n -m1 'authenticatedRestoredCancellation(activeRecord)' \
    <<< "$restore_runtime_body" | cut -d: -f1)"
restore_seed_order="$(grep -n -m1 'restoreSeed(bot, restored.get(), activeRecord)' \
    <<< "$restore_runtime_body" | cut -d: -f1)"
[[ -n "$authenticated_cancel_order" && -n "$restore_seed_order" \
    && "$authenticated_cancel_order" -lt "$restore_seed_order" ]] \
  || fail 'Authenticated restored CANCEL must run before mutable restore seed/world reconstruction'
authenticated_cancel_body="$(sed -n '/static Optional<UUID> authenticatedRestoredCancellation(/,/private static RestoreSeed restoreSeed(/p' \
    "$goal_executor")"
for invariant in \
    'MissionCheckpointCodec.decode(checkpoint)' \
    'persistedSpec.bindingValid()' \
    'missionId.equals(runtime.missionId())' \
    'restoredCancellationRequired(true, policy)'; do
  grep -Fq "$invariant" <<< "$authenticated_cancel_body" \
    || fail "Pre-world restored CANCEL authentication is missing invariant: $invariant"
done
if grep -Eq 'initialContext|validateAndLoadBuildGoal|serverLevel' <<< "$authenticated_cancel_body"; then
  fail 'Authenticated restored CANCEL dispatcher reads mutable world state'
fi
grep -Fq "printf 'recovery_budget\\tnonzero_exact_restore\\n'" \
    scripts/persistence_restart_test.sh \
  || fail 'two-JVM restart evidence does not report nonzero recovery-budget restoration'
grep -Fq "printf 'checkpoint\\tv3_accounting_exact_plan_cursor_rebased\\n'" \
    scripts/persistence_restart_test.sh \
  || fail 'two-JVM restart evidence does not distinguish exact accounting from a legal plan-cursor rebase'
restart_harness=fabric/src/gametest/java/io/github/greytaiwolf/fakeaiplayer/gametest/AIBotRestartHarnessCommand.java
for invariant in \
    'decodedCheckpoint.checkpoint().completedSteps() == 1' \
    'boolean cursorAdvanced' \
    'boolean cursorContinuityValid' \
    'boolean canonicalRestorePersisted' \
    'restart_harness_probe_only' \
    'GoalExecutor.INSTANCE.clearQueue(bot)' \
    'queue_isolated_after_restore_proof'; do
  grep -Fq "$invariant" "$restart_harness" \
    || fail "two-JVM restart harness is missing strengthened evidence: $invariant"
done
bot_persistence=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/persist/BotPersistence.java
restore_body="$(sed -n '/private int loadAndRespawnInternal(/,/public static BotRecord capture(/p' \
    "$bot_persistence")"
mission_restore_order="$(grep -n -m1 'GoalExecutor.INSTANCE.restoreRuntime' \
    <<< "$restore_body" | cut -d: -f1)"
canonical_save_order="$(grep -n -m1 'saveAll(server);' <<< "$restore_body" | cut -d: -f1)"
[[ -n "$mission_restore_order" && -n "$canonical_save_order" \
    && "$mission_restore_order" -lt "$canonical_save_order" ]] \
  || fail 'canonical restore checkpoint must be saved synchronously after Mission and Job reconstruction'
for invariant in \
    'canonicalRestoreWriteAllowed(' \
    'restoredJobIdentityMatches(migratedJobs, restoredJobs)' \
    'restorableBotSubstate(BotRecord record)' \
    'inventoryPayloadValid(record.inventoryNbt())' \
    'BotMemoryStore.INSTANCE.persistedPayloadValid(record.memoryNbt())' \
    'fullyAccounted &= missionAccounted' \
    'quiescePendingWritesForRestore()' \
    'drainPendingWrites();' \
    'return lastWriterOperationSucceeded;' \
    'runtime_restore_writer_quiesce_failed_read_only' \
    'runtime_load_failure_read_only' \
    'runtime_canonical_restore_save_failed_read_only' \
    'TaskManager.INSTANCE.beginRuntimeSession()' \
    'TaskManager.INSTANCE.enterRuntimeRecoveryMode(event)' \
    'runtime_restore_unexpected_failure_read_only' \
    'AIPlayerManager.INSTANCE.all()' \
    'TaskBoard.INSTANCE.suspendClaims(event)' \
    'TaskManager.INSTANCE.acquireRuntimeRecoveryLock' \
    'bot.setInvulnerable(true)' \
    'GameType.SPECTATOR' \
    'runtime_partial_restore_read_only' \
    'source_snapshot_preserved; repair_or_remove_isolated_records'; do
  grep -Fq "$invariant" "$bot_persistence" \
    || fail "Partial restore can overwrite an unaccounted source record: $invariant"
done
ai_player_restore_body="$(sed -n '/public Optional<AIPlayerEntity> respawnFromRecord(/,/public boolean despawn(/p' \
    "$ai_player_manager")"
for invariant in \
    'if (!BotPersistence.restorableBotSubstate(record))' \
    'boolean inventoryRestored' \
    'boolean memoryRestored' \
    'if (!inventoryRestored || !memoryRestored)' \
    'return Optional.empty();'; do
  grep -Fq "$invariant" <<< "$ai_player_restore_body" \
    || fail "Bot substate restore can be silently canonicalized after failure: $invariant"
done
memory_store=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/memory/BotMemoryStore.java
for invariant in \
    'public boolean loadString(' \
    'public boolean persistedPayloadValid(' \
    'persistedRootValid(' \
    'non_exact_round_trip'; do
  grep -Fq "$invariant" "$memory_store" \
    || fail "Bot memory restore can silently drop persisted state: $invariant"
done
for invariant in \
    'public static boolean applyInventory(' \
    'root.get(INVENTORY_KEY) instanceof ListTag inventory' \
    'inventory.getElementType() == Tag.TAG_COMPOUND' \
    'non_exact_round_trip'; do
  grep -Fq "$invariant" "$bot_persistence" \
    || fail "Bot inventory restore can silently drop persisted state: $invariant"
done
load_respawn_body="$(sed -n '/public int loadAndRespawn(/,/public int reloadIfIdle(/p' \
    "$bot_persistence")"
grep -Fq 'catch (RuntimeException restoreFailure)' <<< "$load_respawn_body" \
  || fail 'Unexpected restore reconstruction failures can escape without the global read-only gate'
unexpected_gate_order="$(grep -n -m1 'runtime_restore_unexpected_failure_read_only' \
    <<< "$load_respawn_body" | cut -d: -f1)"
unexpected_return_order="$(grep -n 'return 0;' <<< "$load_respawn_body" | tail -1 | cut -d: -f1)"
[[ -n "$unexpected_gate_order" && -n "$unexpected_return_order" \
    && "$unexpected_gate_order" -lt "$unexpected_return_order" ]] \
  || fail 'Unexpected restore failure returns before installing the global read-only gate'
signal_event_body="$(sed -n '/public boolean signalMissionEvent(/,/public void clear(/p' \
    "$goal_executor")"
grep -Fq 'plan.currentTask == null && !plan.restoredAdmissionPending' \
    <<< "$signal_event_body" \
  || fail 'A restored WAITING Mission event can start a Skill before first-tick safety admission'
if sed -n '/private boolean quiescePendingWritesForRestore(/,/private void enterRestoreReadOnly(/p' \
    "$bot_persistence" | grep -Fq 'pendingAsync.set(null)'; then
  fail 'Restore quiesce discards a pending deletion/update instead of draining it'
fi
grep -Fq 'pendingAsync.get() != null || asyncDrainScheduled.get()' "$bot_persistence" \
  || fail 'Live reload can flush a pre-repair pending snapshot over an authoritative repaired source'
task_board=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/coordination/TaskBoard.java
for invariant in \
    'if (claimsSuspended)' \
    'throw new IllegalStateException("runtime_recovery_read_only")' \
    'public void suspendClaims(' \
    'claimsSuspended = false'; do
  grep -Fq "$invariant" "$task_board" \
    || fail "Partial restore does not suspend Job claims: $invariant"
done
persist_command=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotPersistSubcommand.java
grep -Fq 'BotPersistence.INSTANCE.readOnlyRecoveryActive()' "$persist_command" \
  || fail 'Persist reload command can report success after fail-closed recovery'
runtime_lifecycle=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/runtime/RuntimeLifecycleCoordinator.java
grep -Fq 'server_runtime_recovery_read_only' "$runtime_lifecycle" \
  || fail 'Server startup reports runtime ready after restore entered read-only recovery'
task_command=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotTaskSubcommand.java
brain_command=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotBrainSubcommand.java
job_command=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/command/AIBotJobSubcommand.java
for recovery_entry in "$task_command" "$brain_command"; do
  grep -Fq 'TaskManager.INSTANCE.hasRuntimeRecoveryLock(bot)' "$recovery_entry" \
    || fail "$recovery_entry can report a rejected recovery mutation as successful"
done
grep -Fq 'if (!queued)' "$job_command" \
  || fail 'Operator tell can report an unqueued Brain request as successful'

grep -Fq 'record Exact' common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/NavGoal.java \
  || fail 'P2 NavGoal.Exact contract is missing'
grep -Fq 'record FollowRing' common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/NavGoal.java \
  || fail 'P2 NavGoal.FollowRing contract is missing'
grep -Fq 'Map<SearchState, Double> gScore' \
    common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/MultiGoalAStarPathfinder.java \
  || fail 'P2 multi-goal A* does not retain heading-aware search state'
for pathfinder in \
    common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/AStarPathfinder.java \
    common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/MultiGoalAStarPathfinder.java; do
  grep -Fq 'boolean virtuallyClearedColumn' "$pathfinder" \
    || fail "$pathfinder merges live and virtually cleared DIG columns"
  grep -Fq 'node.moveType() == MoveType.DIG_THROUGH' "$pathfinder" \
    || fail "$pathfinder does not derive virtual-column state from the incoming DIG move"
done
neighbor_enumerator=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/NeighborEnumerator.java
if ! grep -A1 -F 'if (!currentColumnWillBeCleared) {' "$neighbor_enumerator" \
    | grep -Fq 'addPillar(current, world, result);'; then
  fail 'virtual DIG clearance can leak through a PILLAR transition'
fi
path_executor=common/src/main/java/io/github/greytaiwolf/fakeaiplayer/pathfinding/PathExecutor.java
grep -Fq 'candidateIndex == index && !committedEditCompletion' "$path_executor" \
  || fail 'the first pending route edge does not fail closed when its live source column is blocked'
grep -Fq 'candidate.pos().equals(previous.pos().below())' "$path_executor" \
  || fail 'the committed DIG exception is not limited to the expected downward support removal'
grep -Fq 'candidate.moveType() == MoveType.PILLAR_UP && pillarPlaced' "$path_executor" \
  || fail 'pillar completion is not tied to an executor-observed successful placement'
grep -Fq 'currentEditCommitted && !candidateStandable' "$path_executor" \
  || fail 'a committed DIG/PILLAR edit can continue after its target postcondition is lost'
grep -Fq 'boolean committedEditCompletion = currentEditCommitted && candidateStandable' \
    "$path_executor" \
  || fail 'a committed edit completion exception can leak into future lookahead edges'
first_edge_revalidation="$(sed -n \
    '/List<NeighborCandidate> transitions = candidateIndex == index/,/boolean transitionValid/p' \
    "$path_executor")"
grep -Fq '? validator.getNeighbors(previous.pos(), world)' <<< "$first_edge_revalidation" \
  || fail 'the first pending route edge does not revalidate its predecessor against live world state'
grep -Fq ': validator.getNeighbors(previous, world);' <<< "$first_edge_revalidation" \
  || fail 'future route edges do not preserve pending DIG virtual-column semantics'
world_edit_fallback="$(sed -n \
    '/if (!transitionValid/,/if (!transitionValid) {/p' "$path_executor")"
grep -Fq 'transitionValid = transitions.stream()' <<< "$world_edit_fallback" \
  || fail 'a completed world-edit step does not require a live replacement transition'
if grep -Fq 'Standability.isStandable' <<< "$world_edit_fallback"; then
  fail 'a completed world-edit step can bypass live transition validation with standability alone'
fi

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

set_option_body="$(sed -n '/public void handleSetOption(/,/public void handleTeleport(/p' \
    "$server_networking")"
grep -Fq 'rejectRecoveryControl(player, target' <<< "$set_option_body" \
  || fail 'Panel options can mutate runtime state during read-only recovery'
teleport_body="$(sed -n '/public void handleTeleport(/,/private static boolean rejectRecoveryControl(/p' \
    "$server_networking")"
grep -Fq 'rejectRecoveryControl(player, target' <<< "$teleport_body" \
  || fail 'Panel teleport can mutate runtime state during read-only recovery'
teleport_gate_order="$(grep -n -m1 'rejectRecoveryControl(player, target' <<< "$teleport_body" | cut -d: -f1)"
teleport_world_order="$(grep -n -m1 'CapabilityRuntime.decide' <<< "$teleport_body" | cut -d: -f1)"
[[ -n "$teleport_gate_order" && -n "$teleport_world_order" \
    && "$teleport_gate_order" -lt "$teleport_world_order" ]] \
  || fail 'Panel teleport recovery gate must run before capability/world reads'

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

  # The source checks above prove that both wrappers look equivalent. When GameTest results are
  # present, also prove the loaders actually discovered and executed every test. PR CI sets
  # CI_STATIC_CHECK_GAMETEST_RESULTS=1, so missing evidence cannot silently turn this into a jar-only
  # inspection. Local production-jar checks may omit that flag when GameTests were not requested.
  fabric_gametest_report=fabric/build/test-results/gametest/TEST-fakeaiplayer-gametest.xml
  neoforge_gametest_log=neoforge/build/run/gameTest/logs/latest.log
  common_test_results=common/build/test-results/test
  if [[ "${CI_STATIC_CHECK_GAMETEST_RESULTS:-0}" == 1 ]]; then
    [[ -f "$fabric_gametest_report" ]] \
      || fail 'required Fabric GameTest evidence is missing'
    [[ -f "$neoforge_gametest_log" ]] \
      || fail 'required NeoForge GameTest evidence is missing'
    [[ -d "$common_test_results" ]] \
      || fail 'required JUnit evidence is missing'
  fi
  if [[ -f "$fabric_gametest_report" || -f "$neoforge_gametest_log" ]]; then
    [[ -f "$fabric_gametest_report" ]] \
      || fail 'NeoForge GameTest evidence exists but the Fabric JUnit report is missing'
    [[ -f "$neoforge_gametest_log" ]] \
      || fail 'Fabric GameTest evidence exists but the NeoForge latest.log is missing'

    # Fabric's SavingXmlTestReporter emits JUnit-like nested suites without a tests="N"
    # aggregate attribute. Count authoritative testcase nodes instead.
    fabric_executed_total="$(grep -oE '<testcase([[:space:]>])' "$fabric_gametest_report" \
        | wc -l | tr -d '[:space:]')"
    [[ "$fabric_executed_total" == "$expected_fabric_gametests" ]] \
      || fail "Fabric executed GameTest count changed: expected $expected_fabric_gametests, found ${fabric_executed_total:-0}"
    neoforge_executed_total="$(grep -cE \
        "Running test batch '[^']+:[0-9]+' \\(1 tests\\)\\.\\.\\." \
        "$neoforge_gametest_log" || true)"
    [[ "$neoforge_executed_total" == "$expected_neoforge_gametests" ]] \
      || fail "NeoForge executed GameTest count changed: expected $expected_neoforge_gametests, found $neoforge_executed_total"
    grep -Fq "All $expected_neoforge_gametests required tests passed :)" "$neoforge_gametest_log" \
      || fail "NeoForge did not report all $expected_neoforge_gametests required GameTests passing"

    expected_fabric_p3_tests="$(printf '%s\n' "$fabric_p3_delegates" \
        | tr '[:upper:]' '[:lower:]' \
        | sed 's#^#name="fakeaiplayersharedp3gametests.#; s#$#"#' \
        | sort)"
    executed_fabric_p3_tests="$(grep -oE \
        'name="fakeaiplayersharedp3gametests\.[^"]+"' "$fabric_gametest_report" \
        | sort || true)"
    [[ -n "$expected_fabric_p3_tests" \
        && "$executed_fabric_p3_tests" == "$expected_fabric_p3_tests" ]] \
      || fail 'Fabric did not execute exactly the registered P3 GameTest methods'

    expected_neoforge_p3_batches="$(grep -oE 'batch = "[^"]+"' "$neoforge_p3_tests" \
        | sed -E 's/^batch = "([^"]+)"$/\1/' | sort)"
    executed_neoforge_p3_batches="$(grep -oE \
        "Running test batch 'fakeaiplayer_shared_p3_[a-z0-9_]+:[0-9]+' \\(1 tests\\)" \
        "$neoforge_gametest_log" \
        | sed -E "s/^Running test batch '([^']+):[0-9]+' \\(1 tests\\)$/\\1/" \
        | sort || true)"
    [[ -n "$expected_neoforge_p3_batches" \
        && "$executed_neoforge_p3_batches" == "$expected_neoforge_p3_batches" ]] \
      || fail 'NeoForge did not execute exactly one test for every registered P3 batch'
  fi
  if [[ "${CI_STATIC_CHECK_GAMETEST_RESULTS:-0}" == 1 ]]; then
    mapfile -t junit_reports < <(
      find "$common_test_results" -maxdepth 1 -type f -name 'TEST-*.xml' | sort
    )
    [[ "${#junit_reports[@]}" == "$expected_junit_classes" ]] \
      || fail "JUnit report class count changed: expected $expected_junit_classes, found ${#junit_reports[@]}"
    junit_executed_total="$(grep -h '<testsuite ' "${junit_reports[@]}" \
        | sed -E 's/.* tests="([0-9]+)".*/\1/' \
        | awk '{ total += $1 } END { print total + 0 }')"
    [[ "$junit_executed_total" == "$expected_junit_tests" ]] \
      || fail "JUnit executed test count changed: expected $expected_junit_tests, found $junit_executed_total"
  fi

  inspected=0
  while IFS= read -r -d '' jar_file; do
    inspected=1
    if ! jar_listing="$(jar tf "$jar_file")"; then
      fail "invalid or truncated jar: $jar_file"
    fi
    if grep -Eq "$verification_jar_leak_regex" <<< "$jar_listing"; then
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
