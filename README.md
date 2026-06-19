# Blackbox ![Build Status](https://img.shields.io/badge/build-passing-brightgreen?style=for-the-badge) ![License](https://img.shields.io/badge/license-MIT-blue?style=for-the-badge) ![Platform](https://img.shields.io/badge/platform-hytale-orange?style=for-the-badge)

<img alt="Blackbox incident report" src="https://raw.githubusercontent.com/Xytronix/blackbox/dev/docs/report.png" />

> The self-contained `report.html` — the Key metrics page of an incident.

### The flight recorder for Hytale dedicated servers.

Servers break at 3 AM, when nobody is watching. Blackbox is an always-on incident recorder: it keeps a rolling JVM recording (30 min / 512 MiB, ~1% overhead) running at all times, and turns a stall, a crash, or a manual command into a clean, self-contained bundle you can analyze later. Strictly local, safe to leave in production.

## What you get

One zip per incident in `incidents/`:

* **`report.html`** — self-contained analysis page; open in any browser, even offline.
* **`recording.jfr`** — raw Java Flight Recorder data for JDK Mission Control.
* **`incident.json`** — machine-readable metadata.
* **`env/` & `extras/`** — JVM/OS facts, thread dump, plugin list, world list, heartbeats, server-log tail.

Artifacts (except `incident.json`) toggle via `Capture.Artifacts`; disk is capped by retention (25 bundles / 7 days by default).

## The report

`report.html` does the reading for you:

* **Diagnosis** — verdict with a confidence rating, evidence rows, next steps, and baseline-vs-incident deltas.
* **Charts** — tick, TPS, players, ping, CPU, heap, RSS, network, threads, GC, exceptions; zoomable, with an incident marker and log/event lines.
* **CPU** — by world thread, engine subsystem, and mod (exact classloader attribution), plus a flamegraph and the top hot methods.
* **Engine tick costs** — per-system wall-clock timings that CPU sampling can't see.
* **Memory & GC** — heap/RSS, GC causes, allocation pressure by subsystem/thread/class/mod, leak candidates, optional heap histogram.
* **Mods** — plugin list with compatibility verdicts and cross-mod mixin conflict detection.
* **Server log** — parsed, collapsed, filterable; plus one-click "Copy as Markdown."

Sections without data hide themselves.

## Triggers

Configured under `Trigger.*`; a threshold of zero disables a detector.

| Trigger | Fires when | Default |
|---|---|---|
| `HEARTBEAT_STALL` | A world misses its heartbeat | 2000 ms degraded, 10000 ms critical |
| `TICK_DEGRADED` | Tick average stays high | 100 ms degraded, 250 ms critical |
| `WORLD_FAILURE` | A world thread dies | always on |
| `DEADLOCK` | The JVM reports a deadlocked thread set | on |
| `HEAP_PRESSURE` | After-GC heap occupancy stays high | 90% sustained 60 s |
| `GC_PRESSURE` | Fraction of wall time spent in GC | 25% over 60 s |
| `CPU_SATURATION` | Process CPU stays pinned | 95% sustained 60 s |
| `NET_SATURATION` | Interface throughput exceeds your threshold | disabled (0 Mbit/s) |
| `PLAYER_DROP` | Online count collapses | 50% within 60 s, min 8 players |
| `MANUAL` | You ran `/blackbox dump` | n/a |

A global cooldown (30 s) and per-trigger debounce (2 s) stop an unhealthy server from flooding your disk.

## Commands

| Command | Does |
|---|---|
| `/blackbox dump` | Trigger a manual capture |
| `/blackbox status` | Show status |
| `/blackbox list` | List recent incidents |
| `/blackbox open` | Show the incident directory path |
| `/blackbox triggers` | Show every trigger's live config |
| `/blackbox histogram` | Show top heap classes (walks the heap; warns first) |
| `/blackbox profile <minutes>` | Record at high fidelity for N minutes, then capture |
| `/blackbox reload` | Reload `blackbox.json` without a restart (`Jfr.*` changes need a restart) |

## Configuration

`blackbox.json` is written on first start with every key and default. Groups:

* **`Jfr`** — recording window/size, snapshot interval, sample cadence, and preset (`default` ~1% / `profile` ~2%).
* **`Trigger`** — thresholds for the table above.
* **`Retention`** — bundle count / total bytes / max age.
* **`Capture`** — artifact toggles, log-tail length, redaction patterns, heap-histogram opt-in.
* **`Discord`** — webhook alerts; inert until `WebhookUrl` is set.
* **`Metrics`** — opt-in (off by default) long-horizon health CSV at `metrics/health-*.csv` for spreadsheets/Grafana.

## Installation

1. Drop the Blackbox jar into the server's `mods/` directory.
2. Start the server — `blackbox.json` appears with defaults.
3. After an incident, grab the latest zip from `incidents/`.

Open `report.html` for the quick read, or `recording.jfr` in **JDK Mission Control** for the deep dive.

## Optional JVM flags

Blackbox needs no flags (don't pass `-XX:StartFlightRecording` — it isn't read). These optional flags sharpen the report:

* `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` — accurate method sampling (the biggest win).
* `-XX:FlightRecorderOptions=stackdepth=256` — deeper flamegraph stacks (default 64).
* `-XX:NativeMemoryTracking=summary` — adds native/off-heap memory to the report.
* `-XX:FlightRecorderOptions=repository=<path>` — durable crash recovery if your temp dir is RAM-backed or wiped on reboot.

## Plugin API

Mods can feed the report via reflection — no compile-time dependency, and a no-op when Blackbox is absent:

```java
BlackboxApi.registerDiagnostics("My Plugin", () -> Map.of("mode", "fast"));
BlackboxApi.recordEvent("AiThrottler", "throttled 42 entities");
BlackboxApi.recordCount("Chunks unloaded", 180);
BlackboxApi.recordGauge("Entities frozen", 42);
```

Events and metrics ride in the rolling buffer, so they survive crashes and appear on any incident whose window covers them.

## Privacy

Strictly local — nothing leaves the machine by default. Discord alerts are opt-in and never include the bundle. Text artifacts pass through regex redaction (`Capture.RedactPatterns`; IPs and webhook URLs masked by default). Treat a shared bundle like a heap dump.

## Development

Requires JDK 21. Build with `./gradlew build` (or `./gradlew jar`).
