# ks-RPG-Gui 修改日志

## 2026-07-22

- 菜单继续通过 `RpgProgressionApi`/`RpgContentApi` 使用当前目录，不复制内容所有权。
- `menu.yml` 采用异步解析、完整校验后服务器线程原子替换。
- 最终构建通过；仅部署于 Paper/Leaves，不进入 Folia。
