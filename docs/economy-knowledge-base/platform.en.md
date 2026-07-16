# Economy Platform Contract

> [中文](platform.md) | English

`ks-core` owns shared SQLite and Web routing. `ks-Eco` owns currency/item settlement, market transactions, storage,
official buyback, dynamic prices, blind boxes, limited sales, compensation, and Extra loading. Extra modules own their
domain records and routes while using the host settlement contract.

SQL and pure calculations run asynchronously. Bukkit objects, inventories, item metadata, Vault calls, and GUI work
stay on the server thread. Workers receive immutable snapshots and return immutable results; settlement is idempotent.

Player entry points are exposed through `kseco`, `market`, `trade`, `storage`, `limitedsale`, and the economy GUI.
Administrative entry points require the smallest domain permission, normally `kseco.admin` or a module-specific node.
