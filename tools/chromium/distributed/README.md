# Distributed Chromium C++ compilation

This is a compiler-action snapshot workflow. It preserves the paused native
build and distributes selected pending C++ commands to standard GitHub-hosted
Ubuntu runners. It is not Siso/REAPI infrastructure and does not yet distribute
code generation, C compilation, Java, Rust, linking or APK packaging.

The dedicated remote branch is `codex/chromium-distributed`. Its legacy Android
CI job is explicitly skipped to prevent unrelated APK builds and automatic
Telegram delivery. Only explicitly named tooling files are published by
`publish_ci.py`. The main branch and the D:/Projects/Personal checkout are not
modified. No application release is published by these workflows.

## Storage and provenance

Staging directory: `D:/UpgridBuild/distributed-20260930`.
Source checkout/output remain in their existing Ubuntu WSL location. Disk D is
used for manifests, compressed snapshots, returned objects and backup files.
GitHub Release `chromium-distributed-inputs-20260930` is a temporary development
transfer area for compiler inputs and outputs, explicitly marked not an APK.
It is not the latest application release. Each input asset is immutable and
SHA-256 verified. Large object archives use release assets; only small progress
reports/logs use short-lived Actions artifacts.

Both worker and local compiler use the exact original paths, compiler and
command strings. The two-file parity test verified byte-identical object
files and identical Ninja command hashes on independent GitHub runners.
This does not establish correctness of untested actions or a complete APK.

## Wave 1

The pending graph was inspected without executing build commands. Of 20,390
pending actions, 15,841 were C++ commands. Wave 1 selects 15,675 whose explicit
sources/modules exist. The other 166 are deferred for prerequisite generation.
Actions are assigned uniquely to 40 shards using prior compile durations.
Each worker uses four compiler slots and receives one 695 MB snapshot containing
roughly 2.9 GB of compiler inputs. The account's actual concurrency determines
how many shards run simultaneously; queued jobs are expected on lower limits.

The snapshot is immutable for the entire wave. Do not modify Chromium inputs
or run the ordinary build concurrently. Missing headers or real compiler errors
fail the shard; completed objects are still returned. Never weaken compiler
checks to force success. This implementation balances fixed shards; it does not
claim dynamic work stealing. Record observed performance before claiming speedup.

The first full run (36760005966) exposed missing generated headers: it retained
1,120 successful C++ objects, but all 40 shards stopped after eight errors.
This is a failed wave, not APK success. Actual account concurrency was 20
simultaneous jobs. The earlier eight-object pilot was imported successfully.

`continue_wave2.py` waits for verified import of those 1,120 objects, then runs
the reviewed native-input generation graph (767 pending actions, 764 ACTION
and three COPY steps, no C++ compilation), with six local Ninja slots. It
refreshes the original graph's pending commands and builds a new immutable
snapshot. Only after successful generation, hashing and upload does it dispatch
40 balanced shards again. Workers attempt all independent objects even when
some fail; failed compilations remain failures. The supervisor records each
phase and failures in `distributed-build-state.json`. Do not start a second
supervisor or compiler while it is active.

## Accept returned objects

1. Read `distributed-build-state.json` in the feature build folder for the
   current run ID and exact workflow head SHA. `status.py` reads progress checks
   updated about every five minutes without changing the workflow.
2. Download the `objects-RUN_ID-ATTEMPT-SHARD.tar.gz` assets belonging to that
   run from the development transfer release into a new D: subdirectory.
   `collect_wave.py STATE` performs this download with run/head/snapshot checks.
3. In WSL, run `import_wave.py ARCHIVE --run-id ID --head-sha SHA` first. It
   validates the artifact, output allowlist, hashes, dependencies and current
   inputs. Then repeat with `--apply` only at an idle build boundary. For wave 2,
   pass `--snapshot-prefix wave2`; never validate it against the wave 1 snapshot.
   `import_downloaded.py DOWNLOAD_RECEIPT --snapshot-prefix wave2` imports
   downloaded shards serially, verifies before each mutation, and skips shards
   with matching successful import receipts.
4. `before-remote-import` contains original logs and replaced objects. Imports
   merge Ninja dependency IDs instead of copying another runner's entire log.
   The merge was tested against the real Ninja: accepted objects stay cached,
   and changed headers correctly invalidate them.
5. After imports, inspect the original full Ninja graph in dry-run mode. It is
   authoritative for pending generators/dependencies. Speculative objects whose
   prerequisites change may need rebuilding; never suppress that rebuilding.
6. Finish the remaining graph with the approved resource limits, validate the
   fresh build receipt and APK, then perform mandatory Android acceptance.

`worker.py` is the initial parity test. `wave_worker.py` compiles new actions,
keeps completed outputs on errors, and caps compile time below Actions' job limit.
All snapshots and retained outputs are development intermediates, not release
approval. Google/TWP and other Android acceptance remain separate requirements.
