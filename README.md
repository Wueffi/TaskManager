![Static Badge](https://img.shields.io/badge/Version-1.3.0-blue) ![Modrinth Downloads](https://img.shields.io/modrinth/dt/taskmanager)

# Task Manager

Task Manager is a Fabric client profiler for modded Minecraft that helps you identify frame drops, high MSPT, hot mods, rendering spikes, block-entity pressure, and JVM/runtime issues while the game is still running.

Open the profiler in-game with `F12`. Toggle the HUD with `F10` and export Sessions using `F9`.

## Key Features

- Live per-mod CPU attribution with sampled stack ownership and event invokes shown separately
- Estimated per-mod GPU attribution using render-thread sampling plus measured GPU timer-query time
- Per-mod memory estimates, shared JVM/runtime buckets, and top live class families
- Loader-aware startup timing measured from Fabric entrypoint execution and shown explicitly in `ms`
- Timeline graphs for frametime, FPS, network throughput, and disk throughput
- World diagnostics with `Lag Map` and `Block Entities` mini-tabs
- JVM thread diagnostics, waits/locks, and sensor/runtime health in the System tab
- Session recording and export with summarized worst frame, MSPT spikes, top CPU/GPU/memory mods, hot chunk, and block-entity hotspots
- Optional HUD overlay with configurable sections, layout, position, and trigger mode

![Task Manager](https://cdn.modrinth.com/data/cached_images/f984d1f0fa21750fbf4bc77ac8940655ddd826ea.png)

## Tabs

- `Tasks`: per-mod CPU share, sampled threads, sample counts, invokes, filtering, sorting, and row detail
- `GPU`: estimated per-mod GPU share, render samples, thread counts, filtering, sorting, and row detail
- `Render`: render-phase CPU/GPU timings and call counts
- `Startup`: Fabric startup/entrypoint timing by mod in explicit milliseconds
- `Memory`: per-mod heap estimates, shared JVM buckets, top classes, filtering, sorting, and shared-family drilldown
- `Flame`: sampled stack/flame-style hotspot view
- `Timeline`: frametime, FPS, jitter, variance, frame percentiles, GPU frame time, and MSPT overview
- `Network`: inbound/outbound throughput graphs plus packet/category breakdowns
- `Disk`: disk read/write throughput graphs
- `World`: lag map, hot chunks, entity hotspots, and block-entity drilldown
- `System`: CPU/GPU load, temperatures, thread load, waits/locks, sensors, and runtime diagnostics
- `Settings`: session options, HUD options, and configurable table column visibility

![Task Manager](https://cdn.modrinth.com/data/cached_images/dae4562f6718c53e4d2c8feb45d4dceb042368c6.png)

![Task Manager](https://cdn.modrinth.com/data/cached_images/5355d01fc79d7f784245f016c5eb583f8472d4e7.png)

## Controls

- `F12`: open Task Manager
- `F11`: start/stop session recording
- `F10`: toggle HUD overlay

All keybinds can also be changed in Minecraft controls.

## Searching, Sorting, and Columns

`Tasks`, `GPU`, and `Memory` support:

- typed search/filter boxes
- clickable sortable columns
- configurable column visibility from the `Settings` tab

## Sessions and Export

Session exports include a polished summary with:

- worst frame
- worst MSPT spike
- top CPU mods
- top GPU mods
- top memory mods
- hot chunk summary
- block entity classes
- repeated conflict edges and pairwise lock contention summaries
- rule findings and sensor diagnostics

For `MANUAL_DEEP`, press `F11` to start recording, play normally while reproducing the issue, then press `F11` again to stop. The session will continue recording even if the Task Manager screen is closed.

## Notes on Accuracy

Task Manager aims to be honest about what is measured versus estimated:

- CPU attribution is based on sampled stack ownership
- GPU attribution is estimated from render-thread samples weighted by GPU timer-query time
- Memory attribution is based on JVM/class-histogram style ownership and shared runtime buckets
- Temperatures and some system metrics depend on what the platform or an external sensor provider exposes
- For CPU temperature on Windows, running Core Temp is the primary recommended setup; LibreHardwareMonitor/OpenHardwareMonitor and HWiNFO can also work

That means the profiler is very useful for finding hotspots and spikes, but some values, especially GPU-per-mod and shared JVM memory, should be interpreted as guidance rather than perfect ground truth.
