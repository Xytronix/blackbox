# Blackbox ![Build Status](https://img.shields.io/badge/build-passing-brightgreen?style=for-the-badge) ![License](https://img.shields.io/badge/license-MIT-blue?style=for-the-badge) ![Platform](https://img.shields.io/badge/platform-hytale-orange?style=for-the-badge)

<img width="2560" height="1392" alt="image" src="https://github.com/user-attachments/assets/82177e16-cda8-4611-8c52-ca9c7ca3e66a" />

> Generated JFR being viewed in JDK Mission Control
---

## **The Flight Recorder for Hytale dedicated servers.**
Things rarely break when you are staring at the console; they break at 3 AM when you are asleep. Blackbox is an always on incident recorder designed to solve the ambiguity of **"it just crashed."**

When your server stutters, stalls, or terminates, or otherwise has a seizure, Blackbox ensures you have a clean bundle of state to analyze; it eliminates the need to attempt to reproduce the impossible.

It is not a dashboard; it is not a web panel; it is, regrettably, not a box of chicken nuggets. It is a black box.

### But why?

Most observability tools excel at answering "what is slow right now?". Production environments, however, rarely cooperate with live profiling sessions. Blackbox addresses the other common administrative scenario: "I have no idea what happened, and it fixed itself."

Interactive profilers cannot rewind time; Blackbox can. It keeps a rolling JVM recording (30 minutes / 512 MiB by default, roughly 1% overhead) running at all times. When a trigger fires, it waits for the world to recover so the recording covers the lead-up, the stall, and the aftermath, then dumps the buffer and builds the bundle. If the server dies outright, a rolling snapshot written every 5 minutes is rebuilt into a bundle on the next startup; the crash is documented even though nobody was there to ask for it.

## What you get

One zip per incident in `incidents/`, containing:

* `report.html`: a self-contained analysis page. No web server, no port binding, no external resources; open it in a browser, even offline.
* `recording.jfr`: the raw Java Flight Recorder data for JDK Mission Control.
* `incident.json`: machine-readable metadata.
* `env/` and `extras/`: JVM and OS facts, thread dump, plugin list, world list, heartbeats, and the server log tail.

Every artifact except `incident.json` can be toggled via `Capture.Artifacts`. Disk usage is capped by retention policy (25 bundles / 1 GiB / 7 days by default).

## The report

`report.html` is the part you will actually read at 3 AM, so it does the reading for you first:

* **Diagnosis**: evidence rows with hard numbers and links into the detail sections, a verdict with a confidence rating, and concrete next steps. Plus a baseline vs incident comparison: the calm window against the incident window, per metric, with the bad deltas in red.
* **Charts of everything over the window**: tick interval, TPS, players, ping, CPU, heap, RSS, network, threads, GC pauses, exceptions thrown. Drag to zoom, time-range presets, an incident marker with a subtle incident-window tint, and event lines (log errors and warnings, `System.gc()` calls, plugin-reported moments) folded into one crosshair tooltip.
* **CPU, three ways**: by world thread, by engine subsystem (20 categories), and by mod, with exact attribution via plugin classloader identity. Plus a flamegraph of the full call tree (click to zoom, filter), the top 50 hot methods with dominant call paths, hot threads, and a per-thread state timeline.
* **Engine system tick costs**: the engine's own per-system wall-clock timings, as a table and as charts. This catches systems that block on locks or I/O, which CPU sampling is structurally blind to.
* **Memory & GC**: heap and RSS charts, GC causes ("Allocation Failure" vs "System.gc()" is half the diagnosis), per-tick GC overlap marked in amber on the tick chart, explicit GC attribution, allocation pressure by subsystem, thread, class, and mod, long-lived allocation leak candidates, and an optional exact heap histogram (`Capture.HeapHistogram`, off by default because walking a large heap pauses it for seconds).
* **The environment**: CPU brand, container runtime (Docker / Kubernetes / Podman) with cgroup limits and a warning when `-Xmx` overcommits the container, the full start command, and the Hyinit/Hyxin loader versions.
* **Mods**: the plugin list with the engine's own compiled-for compatibility verdicts, the mixin inventory per config, and cross-mod mixin conflict detection down to the class being patched twice.
* **The server log**: parsed, repeated lines collapsed, stack traces folded, filterable.
* **Copy as Markdown**: the whole analysis as a paste-ready summary for your issue tracker or group chat.

Sections without data hide themselves; a minimal capture renders a minimal page.

## Triggers

All configurable under `Trigger.*` in `blackbox.json`; a threshold of zero disables a detector.

| Trigger | Fires when | Default |
|---|---|---|
| `HEARTBEAT_STALL` | A world misses its heartbeat | 2000 ms degraded, 10000 ms critical |
| `TICK_DEGRADED` | Tick average stays high | 100 ms degraded, 250 ms critical |
| `WORLD_FAILURE` | A world thread dies | always on |
| `DEADLOCK` | The JVM reports a deadlocked thread set | on (exact; bypasses the capture cooldown) |
| `HEAP_PRESSURE` | After-GC heap occupancy stays high | 90% sustained 60 s |
| `GC_PRESSURE` | Fraction of wall time spent in GC | 25% over 60 s |
| `CPU_SATURATION` | Process CPU stays pinned | 95% sustained 60 s |
| `NET_SATURATION` | Interface throughput exceeds your threshold | disabled (0 Mbit/s); measures flooding, cannot judge intent |
| `PLAYER_DROP` | Online count collapses | 50% drop within 60 s, minimum 8 players |
| `MANUAL` | You ran `/blackbox dump` | n/a |

A global cooldown (30 s) and per-trigger debounce (2 s) keep an unhealthy server from carpet-bombing your disk.

## Commands

| Command | Does |
|---|---|
| `/blackbox dump` | Trigger a manual incident capture |
| `/blackbox status` | Show Blackbox status |
| `/blackbox list` | List recent incidents |
| `/blackbox open` | Show incident directory path |
| `/blackbox triggers` | Show every trigger's live configuration |
| `/blackbox histogram` | Show the top heap classes (walks the heap; it warns you first) |
| `/blackbox profile <minutes>` | Record with the high-fidelity profile preset, then capture. Honest trade: starting the session discards the current rolling buffer |
| `/blackbox reload` | Reload `blackbox.json` without a restart. Everything hot-swaps except the `Jfr.*` recording settings; a broken config keeps the previous one running |

## Configuration

A complete `blackbox.json` with every key and default is written next to the plugin on first start; that file is the reference. The groups:

* `Jfr`: window size and age, recording name, disabled events, post-incident wait, snapshot interval, `SampleInterval` (metrics sampling cadence, default 10s, clamped to 5s-5m), and `Configuration` ("default" for ~1% overhead, "profile" for high-fidelity sampling at ~2%).
* `Trigger`: the table above.
* `Retention`: bundle count, total bytes, max age.
* `Capture`: artifact toggles, log tail length, redaction patterns, `HeapHistogram` opt-in.
* `Discord`: webhook notifications. Inert until `WebhookUrl` is set.

## Plugin API

Mods can feed the report. The Refixes fork does exactly this; its throttler and chunk unloader chart their work on every incident.

```java
// A key/value card in every report, sampled at capture time:
BlackboxApi.registerDiagnostics("My Plugin", () -> Map.of("version", "1.0", "mode", "fast"));

// A notable moment, drawn as a marker on the report's chart timelines:
BlackboxApi.recordEvent("AiTickThrottler", "throttled 42 entities in world default");

// Numeric metrics, charted over the window. Counts sum per interval; gauges step:
BlackboxApi.recordCount("ChunkUnloader unloaded", 180);
BlackboxApi.recordGauge("AiTickThrottler frozen", 42);

// Extra files in every bundle:
BlackboxApi.registerExtras((report, event) -> List.of(
    new BundleAttachment("extras/my-plugin.txt", myData.getBytes())));
```

Events and metrics are written into the rolling JFR buffer, so they survive crashes and appear on any incident whose window covers them. Integrating via reflection keeps your mod free of a compile-time dependency; calls are cheap and everything is a no-op when Blackbox is absent.

## Privacy & Security

The default behavior is strictly local.

* Data never leaves the machine automatically.
* There are no third party service hooks.
* Discord integration is optional and disabled by default; it sends only a status alert, never the bundle itself.
* Text artifacts and report inputs pass through configurable regex redaction (`Capture.RedactPatterns`). The defaults mask IPv4 and IPv6 addresses and Discord webhook URLs; add your own patterns (UUIDs, credential pairs) when sharing bundles publicly. The report page itself contains no masking logic; it renders what the pipeline gives it.

If you intend to share a bundle publicly, treat it with the same caution as a heap dump.

## Performance

Blackbox adheres to a strict "do no harm" policy.

* **Thread Safety**: World threads are sacred; no blocking I/O occurs on critical ticks.
* **Isolation**: Capture work is offloaded to Blackbox owned executors.
* **Overhead**: ~1% for the default recording profile, ~2% for `profile`. Long-lived object sampling is bounded by the JVM's fixed sample queue. The exact heap histogram is opt-in precisely because it is not free.
* **Bounded Resources**: Disk usage is strictly capped by retention policies (count, age, total bytes).
* **Graceful Failure**: Incident capture is best effort; server stability always takes precedence over reporting.

## Installation

1. Place the Blackbox jar into the dedicated server `mods/` directory.
2. Start the server. `blackbox.json` appears with all defaults.
3. Upon failure, retrieve the latest archive from the `incidents/` directory.

Blackbox is designed to be a permanent resident in your production environment; it is safe to leave installed.

### How to Use the Analysis?

#### The Quick Read
Unzip the bundle and view `report.html`. It summarizes, charts, and diagnoses the state of the server around the incident; it is designed to be legible to tired system administrators.

#### The Deep Dive
Open `recording.jfr` in **JDK Mission Control** for the raw event stream behind every chart in the report.

## Relationship to other tools

Blackbox now covers most of what an interactive profiler shows, but after the fact. Retain your existing toolkit for live experiments; use Blackbox for the incidents you missed.

## Development

To build the project locally, ensure you have JDK 21 installed.

```bash
./gradlew build
```

You can also produce a jar with:

```bash
./gradlew jar
```
