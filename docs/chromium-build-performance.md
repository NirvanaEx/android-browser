# Cloud build critical path audit

## Measured baseline

Run `37133916096`, preparation log: 11,729 completed Ninja actions from
16:02:00 to 18:17:35 UTC on 2026-10-03 (135 minutes). These were not merely
downloads or header generation:

| Action | Count |
| --- | ---: |
| CXX | 7,800 |
| CC | 467 |
| ACTION | 2,850 |
| AR | 245 |
| ASM | 124 |
| RUST | 123 |
| CXX_MODULE | 99 |
| LINK | 21 |

The long tail includes `clang_x64_v8_arm64/obj/v8/` compilation. The former
pipeline distributed only Android C++ objects **after** this work finished.
Host-tool compilation and Android C compilation were excluded by its filter.
The subsequent draft-creation failure lost the prepared state in that run.

## Changes by stage

| Stage | Change | Remaining constraint / verification |
| --- | --- | --- |
| Source setup | Four parallel cache downloads and four gclient sync jobs on GitHub; reuse successful pinned setup from a verified workspace if DEPS, gclient metadata, setup script, pins and tool hashes match | Source/depot HEAD and ownership are still checked, depot bootstrap still runs on each fresh VM, missing/mismatching setup falls back to sync/hooks; local defaults unchanged |
| Host preparation | Query actual Ninja direct inputs, including implicit/order-only dependencies and validations; build only this dependency cut | Bootstrap tools needed to generate compiler inputs still run here; `mode=plan` measures this cut on real Chromium |
| Graph planning | Batch dependency queries by the OS argument-byte budget, rather than reloading the complete Ninja graph every 128 targets | Current audit started before this optimization; its planning duration is not representative of the latest implementation |
| Host compilation | Separate balanced host C/C++ matrix using the existing exact-command, hash-verified object transfer | New matrix needs a full production-sized trial; fixture tests are not a timing result |
| Android preparation | Restore host checkpoint, validate/import objects, link tools and generate the remaining inputs | Genuine dependencies cannot execute before their inputs; no timestamp tricks |
| Android compilation | Both C and C++ in the distributed matrix, with matching object/dependency validation | Rust and assembly stay in Ninja until supported input/output transfer is implemented and tested |
| Warm rebuild | Fewer than 128 pending host objects stay on the first VM, skipping the host matrix and the extra ~20 GB compressed checkpoint restore; record the routing decision | Threshold is an explicit heuristic pending production timing; cache identity, source changes and command hashes still invalidate work |
| Snapshot creation | Concurrent workspace/native packaging, threaded gzip, bounded overlap of compression/uploads | No compilation may write to a tree while it is snapshotted |
| Snapshot inventory | Deduplicate parent license-directory scans instead of checking the same directories for each of ~220,000 files; include extensionless standard-library headers for host compilers | Archives retain compiler/license/input checks |
| Final import | Parallel downloads/validation; stream object extraction without rescanning gzip for extraction | A single writer updates Ninja logs only after every shard validates |
| Link/APK | Separate restore/import and assembly steps with duration receipts; optimized profile keeps LTO/PGO disabled and symbols stripped | Final linking is one process on one hosted VM; additional independent VMs cannot provide it pooled RAM |
| Android tests | Dispatch immediately after signed APK identity/hash verification and upload; save full incremental cache concurrently | Dispatch receipt is not acceptance. Exact APK evidence, security-base approval and publication gates remain required |

No paid larger runners, local compilation or local Android emulator were added.
The current full run `37145804732` remains on `61a1f2c`; changing workflow files
does not alter that running job. Do not start a second full build alongside it.

## Validation and adoption

Cloud runs `37152765598` and `37152970468` passed tooling, real Ninja host-object
import/invalidation, dependency-cut, archive and regression checks. The separate
audit run `37152774497` restores the actual Chromium cache and performs a dry
plan without compiling Chromium. Inspect `cloud-prepare-diagnostics` for
`host-plan-audit.json`, `host-input-tasks.log` and duration receipts.

Latest implementation check `37154467699` passed after adding bounded large
query batches. Audit `37152774497` completed source restoration/setup/GN in
20 minutes 57 seconds (20:49:40–21:10:37 UTC), using the old source seed without
the new setup marker. This is a baseline for the source step, not evidence that
warm setup reuse has been timed on a full subsequent build.

The real-tree audit completed successfully: 8,034 pending host candidates,
8,178 direct inputs, and 4,316 bootstrap actions. The bootstrap includes 3,650
CXX and 47 CC actions, 214 generators, 164 archives, 123 assembly actions,
66 compiler modules, 41 Rust actions and 11 links. Thus the initial single-VM
barrier drops from 11,729 to 4,316 actions; this is a graph count, not a measured
63% reduction in total build time. Roughly 4,337 additional host compiler actions
can move into the early matrix, subject to Ninja's actual post-bootstrap plan.

The audit's old 128-target query loop took 789.4 seconds; byte-budget batching
was added while it ran and passed separate tests. No second full build was
started to benchmark the change while `37145804732` was active.

In that active run's now-completed preparation log, Ninja ran from 19:16:21
to 20:58:18 UTC (about 102 minutes; the earlier 135-minute baseline varies by VM).
Every action in the new bootstrap cut had completed by 19:44:32, about 28 minutes
after Ninja started, while competing with other work in the old graph. Snapshot
creation then finished at 21:06:49 and the prepare job at 21:22:18. A third wave
with another full workspace transfer/restore would add overhead comparable to
that remaining bootstrap barrier. It is not enabled without evidence of net
benefit. A future persistent coordinator could remove those transfers, but is
not implemented or claimed as validated by this change.

A successful dry plan does not prove a full host wave compiles. Before claiming
speedup, compare actual setup/bootstrap/matrix/restore/link timings against the
baseline, including the extra checkpoint transfer. Keep investigating if the
dependency cut still pulls most host compilation into bootstrap. Do not disable
dependency checks, change browser features or publish an unaccepted APK for speed.
# Final-stage safeguards (2026-10-04)

Run 37156601112 completed all 40 native shards, but final workspace restore/import
took 27m42s and the combined build step remained active for over 30 minutes.
The cause inside that step was not established: the running-job log API returned
404. Neither its step name nor `in_progress` proves linking or forward progress.

Future runs audit the final Ninja plan **after** overlay/GN regeneration. Any
scheduled CC/CXX output already imported from a worker stops the build with
`final-plan.json`; 128 or more remaining undistributed CC/CXX actions also stop
it rather than silently spending hours on one runner. This is a diagnostic
performance guard, not permission to ignore dependencies or force timestamps.

During final Ninja, a separate GitHub Check updates every two minutes. Local
receipts update every 30 seconds with completed action counts, last completed
action, elapsed time, descendant process names, cumulative CPU time and RSS.
No process arguments or environment are published. A ten-minute interval without
a completed action emits a warning, not a false hang verdict or automatic kill
of a legitimate long linker. The existing job timeout and disk guard remain.
`final-build.log`, `final-tasks.log`, `final-plan.json`, `final-progress.json`
and its JSONL history are retained on both success and failure. API outages do
not turn a build into a failure; diagnostic files remain available afterward.
The progress check is only Ninja progress, never APK or Android acceptance.

These safeguards improve diagnosis and prevent hidden recompilation. They do
not yet remove the measured full-workspace transfer or prove a faster complete
release. The already-running 15ee93f workflow cannot acquire these changes.

## Completed final-stage evidence (2026-10-04)

Build 37156601112 succeeded. The assemble step ran 23:43:42–00:43:35 UTC
(59m53s), after 27m42s of restore/import. Its final Ninja executed 9,945
actions: ACTION 7,348; AR 1,951; ASM 256; CC 232; CXX 16; RUST 120;
RUST(MACRO) 4; RUST(BIN) 2; COPY 9; LINK 6; SOLINK 1. Thus this was not
just APK packaging after parallel C++. The log records completion of SOLINK
at 00:40:05, R8 at 00:41:52, APK creation at 00:42:00 and lint at 00:43:15.
Those timestamps alone do not measure the individual operations' durations.

Of the 248 remaining CC/CXX descriptions, 190 are XNNPACK objects whose paths
contain `=` (for example `f16-avgpool_arch=armv8.2-a+fp16`). The shared
`object_path` allowlist excluded that character, so the planner omitted those
objects from the native wave. The corrected allowlist permits literal `=`
while rejecting traversal and shell/Ninja metacharacters. Regression tests
exercise planner routing and the actual worker Ninja/depfile grammar on Linux.
The final audit also recognizes quoted descriptions, so an imported XNNPACK
object cannot evade the repeated-compilation guard.

The other 58 objects (56 clang_x64 and two plain obj paths) need separate
routing analysis; the warm host-wave threshold intentionally leaves small
host rebuilds. The >=128 guard remains unchanged. Moving 190 actions does not
prove a 190/248 reduction in wall time, and cannot explain all 7,348 ACTIONs.
No second full build was launched solely to benchmark this fix.
