# Changelog

## 1.4.0

Minecraft 26.3 update with Iris shader-pass GPU profiling and a lower-overhead profiler.

### Added

- Iris shader-pass GPU timings (composite, shadow-composite and final passes, including compute-only passes) using asynchronous GL timestamp queries, with a sortable table in the Render tab and CSV export
- Bounded outstanding GPU queries with frame and session IDs; dropped samples are counted instead of blocking
- `captureWindows` export section: capture window lengths, sample interval, profiler self-cost, GPU-metric description and shader-pass coverage
- "Refresh Heap" button in the Memory tab for on-demand class histograms

### Changed

- Updated to Minecraft 26.3 (Fabric Loader 0.19.5, Fabric API 0.161.0, Loom 1.17.21, Gradle 9.6.0, Java 25)
- The Timeline "GPU frame" value is now labelled "GPU span": it is the GL timestamp span of the render call, includes idle and overlap, and is not GPU busy time
- JVM and session sampling follow the configured metrics interval (default 500 ms) instead of every tick

### Fixed

- Frame drops from background collection: the Windows PowerShell telemetry helpers now stop when collection is inactive, and heap histograms no longer run automatically
- GPU frame time no longer reports accumulated phase time (previously showed values such as 532 ms)
- README listed F11 for session recording; the key is F9

### Known limitations

- Shader-pass totals cover Iris composite/shadowcomp/final passes only, not geometry, shadow maps or Voxy
- Captures are rolling windows, not the full session; profiler overhead has not been benchmarked against an independent profiler

## 1.2.4

Task Manager 1.2.4 focuses on conflict analysis, clearer attribution, and more reliable session capture.

### Added

- Pairwise lock attribution that records both the waiting thread and the lock owner thread when the JVM exposes that information
- Repeated conflict tracking across the rolling capture window so the mod can highlight recurring `waiter mod -> owner mod` contention instead of isolated one-off waits
- Broader thread role classification for worker pools, IO pools, chunk generation, chunk meshing, chunk upload, network, GC, render, and main logic threads
- Alternate owner candidates in thread drilldown so low-confidence ownership can be reviewed instead of being flattened into a single guess
- Dedicated conflict findings in the UI and HTML export, split into confirmed contention, repeated conflict candidates, weak heuristics, and unrelated slowdown causes

### Changed

- Scheduling-conflict detection no longer depends only on `Worker-Main-*` thread names and now uses thread names plus stack ancestry
- Conflict wording is more conservative and now distinguishes `Measured`, `Inferred`, `Pairwise inferred`, `Weak heuristic`, and reserved support for `Known incompatibility`
- Lock summaries prefer pairwise conflict phrasing when both sides of a wait can be identified
- Export summaries and diagnosis text now include top conflict candidates instead of only generic lock warnings
- Thread detail views now expose role labels, role-source labels, and alternate owner candidates

### Fixed

- `MANUAL_DEEP` recording now continues to capture session samples after recording starts, even if the Task Manager screen is closed
- Session exports no longer fall back to `No session samples were captured` for valid `MANUAL_DEEP` recordings started with `F11`
- Conflict and slowdown reporting no longer overstates certainty by calling inferred waits confirmed mod conflicts

### Notes

- `Known incompatibility` is currently a confidence tier and UI label, not a populated incompatibility database. In 1.2.4, conflict findings are still based on measured contention plus inferred ownership rather than a bundled registry of hardcoded bad mod pairs.
