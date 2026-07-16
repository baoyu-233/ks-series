# KS-Series Server Performance Report

> [中文](PERF_REPORT.md) | English

This report summarizes the performance risks observed in the test server and the order in which they should be
handled.

## Main Findings

The largest risks are excessive simulation distance, entity pathfinding, chunk and lighting work, third-party content
plugins, synchronous SQLite access, and high-frequency item metadata operations. These risks compound under a live
player load; a single idle-server profile is not enough to approve a change.

## Plugin Priorities

1. Move SQL and disk work away from the server thread.
2. Snapshot Bukkit objects before asynchronous processing and return immutable results.
3. Avoid repeated `ItemStack`/`ItemMeta` reads in hot listeners and GUI refresh loops.
4. Cache map tiles and expensive external-plugin lookups.
5. Measure MSPT, heap, and GC after each change with a repeatable load profile.

## Not Every Cost Is a Bug

World generation, lighting, pathfinding, ItemsAdder, and MythicMobs can dominate a profile even when KS code is not
the cause. Validate configuration and compare a controlled baseline before changing plugin behavior.

See [PERF_REPORT.md](PERF_REPORT.md) for the original measurements and detailed recommendations.
