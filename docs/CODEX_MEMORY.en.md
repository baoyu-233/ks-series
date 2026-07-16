# Codex Project Memory

> [中文](CODEX_MEMORY.md) | English

This file is the maintainer handoff index for ks-Series. It records durable project preferences, deployment targets,
verified artifacts, plugin boundaries, test-server facts, and known gaps. It is an index, not a replacement for current
source code, configuration, schema, or live evidence.

Before implementation, read this file and the [codebase map](CODEBASE_MAP.md) together with its [Chinese companion](CODEBASE_MAP.zh-CN.md).
After material behavior, deployment, incident, or verification changes, update the memory and keep superseded facts
out of the active summary. Never store credentials, tokens, runtime databases, private assets, or unredacted logs.

Documentation maintenance rule: every published Markdown document has a Chinese and English companion linked at the
top. The scheduled GitHub documentation check runs weekly and on Markdown changes; Codex should also recheck the pair
inventory when syncing source and publishing checkouts.
