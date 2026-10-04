# Upgrid cloud continuation

## Scope and repository

The owner requested continuing this work in Codex Cloud with their PC off,
integrating with GitHub to investigate failures, fix causes and rerun checks.
Continue the existing work, not a schedule of unrelated releases.
Repository: NirvanaEx/android-browser. Branch:
codex/apk-size-player-isolation-20261003. Existing draft PR: #8.
The phone-feedback draft is committed as e13848b14ff9f955bf4d84516b853c3761b3caf7.

Read AGENTS.md, tools/chromium/ci/README.md,
docs/chromium-dot9-phone-followup.md, docs/chromium-test-coverage.md,
docs/chromium-size-player-audit.md and docs/chromium-build-performance.md first.
Current work is Chromium; do not restart the old Fenix migration or overwrite
other checkouts. This document does not establish a successful cloud migration.

## First priorities

1. Fix the reported phone failures: native mobile Chrome translation, site
   fullscreen entering Upgrid controls, and an app crash on player exit.
   Existing source changes are unverified drafts, not proven fixes.
2. Read the completed SUCCESS diagnosis run 37167752724, artifact
   release-diagnostics-37167752724. It contains exact .9 BuildId checks,
   symbolication (when matching symbols were found), and Ninja timing evidence.
   Separate the emulator startup failure from the phone's player-exit crash.
3. Validate the overlay against the pinned Chromium tree in GitHub. Update the
   stale container-fullscreen assertion in ci/android_test.py to the intended
   isolated-video behavior, with permission, iframe and lifecycle coverage.
4. Investigate native translation service availability. Restoring the UI alone
   is insufficient; the pinned code checks HasAPIKeyConfigured and no Google
   API key was configured at handoff. Never use a fake key or claim success.

The user cannot connect the phone now. Do not repeatedly ask for USB access.
Their four scripts are inventoried in
tools/chromium/ci/fixtures/userscripts-inventory.json; do not ask for them again.

## GitHub execution loop

Use the cloud workspace for source edits and light checks. All Chromium
compilation, GN/Ninja/Gradle, linking, cache analysis and Android emulators run
on GitHub-hosted runners via existing workflows. Do not download the full
Chromium workspace into this small cloud VM or depend on the owner's PC.

First verify repository write access and permission to inspect/dispatch Actions.
Never assume local GitHub or SSH credentials have transferred. Use authorized
integration credentials; do not copy personal tokens or private SSH keys.
Inspect actual workflow inputs before dispatch, and identify runs by exact
head SHA and inputs. Keep at most one full build active. Reuse the verified
cache. Read failed logs and artifacts, fix the cause, commit and push, then
rerun appropriate checks. Do not endlessly retry identical failures or start
full builds just for diagnostics. Network retries must be bounded.

Run regression/tooling checks before a new full build. Preserve the final
plan guard against recompiling imported objects or leaving >=128 CC/CXX
actions outside the matrix. The XNNPACK '=' path fix is already in fd33246.
Investigate the remaining host/wrapped objects and Java/Rust/link timing using
the recovered Ninja evidence. Counts are not elapsed time; do not claim all
bottlenecks removed without a measured full run.

After building, verify APK package/version/code/signer, exact run/head and SHA.
Check for an already-dispatched Android run before creating another. Inspect
results.json, acceptanceCoverage, logcat and screenshots. Test actual frames,
play/pause, fullscreen selection, repeated entry/exit, background, tabs,
rotation, menu, empty and saved-tab cold starts, crashes/ANRs, normal extension
installation and permissions, real userscripts, AdBlock and full translation.
Do not equate mock/unit/smoke tests with Android acceptance. Never disable
DCHECK, sandbox or security checks to obtain a passing result.

Continue this bounded repair/build/test cycle until the requested changes pass
the applicable acceptance checks or a concrete external dependency blocks
progress. Report actionable blockers with evidence and completed artifacts.
Do not promise that all possible bugs are eliminated. Keep unchanged-state
updates quiet; report meaningful results, failures and required user actions.

## Existing artifact: do not republish

Full build 37156601112 succeeded on
15ee93fd7dcc2900c1f8f0542a168113499b059a.
Private draft upgrid-ci-37156601112-1, numeric release ID 402705976.
ChromePublic.apk asset ID 608826815, 444945118 bytes, SHA256
c883a7688a020690e2c91a631cf7a6ed721f3b215495fe162af98cbf072bc849.
Package com.upgrid.chromium, version
146.0.7680.31-upgrid.9-isolated-player-test, code 768003112. Signer SHA256:
32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0.
Access private drafts by numeric ID or authenticated releases listing.

Android run 37165824022 failed on API36 x86_64 with ARM64 translation.
The .9 renderer/GPU had SIGSEGV in berberis_HandleNoExec; the main process
aborted at !tls_blocking_disallowed, thread_restrictions.cc:62.
Exact libchrome BuildId: 849f83441c984f6f. Functional acceptance was blocked.
Do not blame only the baseline or claim an emulator-only defect without proof.

The owner explicitly authorized ONE test publication despite incomplete
acceptance. It was delivered and verified: Telegram message 161,
https://t.me/c/4335405613/161, catalog release 51, app_id upgrid.
Do not send it again. Android acceptance and production/distribution approval
remain false. Any new distributed APK needs a versionCode above 768003112.
Future production publication requires the existing acceptance/security gates.

Before future authorized publication read the live VPS apk-relay AGENTS.md and
RELEASING.md through an available authorized server connection. Preserve
app_id, history and settings; verify message_id, catalog, Bot API file access,
fresh polling and verify_release.py receipt. If cloud has no authorized relay
access, report that dependency instead of inventing delivery or copying keys.

## Migration state at preparation

The official Codex Cloud CLI returned environment_repo_access_failed for
NirvanaEx/android-browser. The owner must enable this repository in the Codex
GitHub integration, then select/create and publish its cloud environment.
No cloud task has been verified as started. The local upgrid-9 heartbeat is
paused; do not reactivate it as a substitute for cloud execution. Leave the
other chat's old upgrid-chromium automation paused.
