# ksHWP 修改日志

## 2026-07-22

- 地图渲染把 ChunkSnapshot 分批抓取留在服务器线程，着色、合成、PNG 和磁盘缓存移到工作线程。
- zoom 2/4/8 不再递归生成大量中间图块；同图块并发请求复用 future。
- 地图标注 schema 改为 MariaDB 可用的 VARCHAR/元数据索引边界。
- 最终构建通过；Leaves 地产地图实机验收正常。
