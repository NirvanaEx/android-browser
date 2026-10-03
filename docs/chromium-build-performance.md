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
| Source setup | Four parallel cache downloads and four gclient sync jobs on GitHub; local resource defaults unchanged | Full workspace still needs decompression; GN validates changed configuration |
| Host preparation | Query actual Ninja direct inputs, including implicit/order-only dependencies and validations; build only this dependency cut | Bootstrap tools needed to generate compiler inputs still run here; `mode=plan` measures this cut on real Chromium |
| Host compilation | Separate balanced host C/C++ matrix using the existing exact-command, hash-verified object transfer | New matrix needs a full production-sized trial; fixture tests are not a timing result |
| Android preparation | Restore host checkpoint, validate/import objects, link tools and generate the remaining inputs | Genuine dependencies cannot execute before their inputs; no timestamp tricks |
| Android compilation | Both C and C++ in the distributed matrix, with matching object/dependency validation | Rust and assembly stay in Ninja until supported input/output transfer is implemented and tested |
| Warm rebuild | If no host objects remain, skip the host matrix and the extra checkpoint restore; continue on the first VM | Cache identity, source changes and command hashes still invalidate work |
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

A successful dry plan does not prove a full host wave compiles. Before claiming
speedup, compare actual setup/bootstrap/matrix/restore/link timings against the
baseline, including the extra checkpoint transfer. Keep investigating if the
dependency cut still pulls most host compilation into bootstrap. Do not disable
dependency checks, change browser features or publish an unaccepted APK for speed.
