# ksHWP v1.2.0 — 硬世界地图

> [English](README.en.md) | 中文

公共社区地图插件。将 Minecraft 世界渲染为 Web 地图，支持多维世界、玩家标注、区域管理。

## 功能

- 🗺️ **Web 地图**: 俯瞰地形渲染，缩放 1-8x，平移拖拽
- 🌍 **多维度**: 主世界、地狱、末地独立地图，一键切换
- 🔴 **未探索标记**: 无人去过的区域显示红色"未探索"块
- 💾 **持久化磁盘缓存**: 渲染的图块永久保存至 `plugins/ksHWP/tiles/`
- 🔄 **跨 zoom 合成**: 缩小地图时自动从详细图块拼接，不丢失已探索区域
- 📝 **地图备注**: 点标注 + 区域标注，分类搜索，支持 7 种类型
- 👥 **实时玩家位置**: 在线玩家绿点标记，支持隐藏模式
- 🔄 **玩家区域更新**: 右键 → 更新此区块（反映建筑变更）
- 🔒 **隐私保护**: 私有备注仅自己可见，其他玩家备注不泄露
- 🗑 **管理员缓存管理**: 清除指定世界全部缓存
- 🌐 **可选跨服投影**: 把已完成渲染的冻结图块按世界组成有界 bundle 发布到 ks-Eco，供获授权节点读取；未安装 ks-Eco 时保持本地模式

## 玩家使用

游戏内输入 `/map` 获取链接。详细指南 → [GUIDE.md](GUIDE.md)

## 管理员命令

```
/kshwp status                                   查看状态
/kshwp reload                                   重载配置
/kshwp forcerender [世界]                        强制渲染已加载区域
/kshwp forcerender-area <世界> <x1> <z1> <x2> <z2>  指定区域渲染
/kshwp prerender [世界]                          预渲染
/kshwp cache                                    缓存统计（内存+磁盘）
/kshwp clearcache [世界]                         清除磁盘缓存
```

## 权限

| 权限 | 说明 | 默认 |
|------|------|------|
| kshwp.admin | 管理命令 | op |
| kshwp.use | 使用地图 | true |
| kshwp.note | 添加备注 | true |
| kshwp.hidden | 隐藏位置 | op |

## 配置

`plugins/ksHWP/config.yml`:
```yaml
map:
  default-world: "world"
  max-chunks-per-request: 256
  show-players: true
pre-render:
  enabled: true
  radius: 5
  zoom-levels: [2, 4]
annotations:
  max-per-player: 100
  max-text-length: 200
cross-server:
  map-publish:
    enabled: false
    min-interval-seconds: 15
    max-payload-bytes: 1048576
web:
  route: "/kSHWP"
```

## 依赖

- **ks-core**: Web 网关 + 鉴权 + 数据库
- **ks-Eco（可选）**: 跨服 MAP 投影；需同时启用其 `cross-server.federated-assets`
- bundle 在 HWP 重启后为空，会随实际图块请求渐进恢复；内存/磁盘缓存命中也参与发布。2026-07-23 实机验证同源 3 图块、非 stale、节点在线。
- Paper 1.21.11+
- Java 21+

## 构建

```bash
cd ksHWP && mvn clean package
```
