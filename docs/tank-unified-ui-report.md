# 坦克状态栏、绘制与保护效果统一报告

日期：2026-11-18。实现已完成，等待手动验收。

## 显示统一

- 单机、联机共用 `BattleFrame` 和 `BattleHud`，剩余血量与得分位于战场右上方。联机额外显示队友血量。
- 单机保留本局得分；合作使用服务端击毁数量计算团队得分，每辆敌军 100 分，两端显示同一团队总分。
- 基地状态、击毁进度、场上敌军和时间统一放在状态栏左侧，移除原来分散的血量、得分面板。
- 坦克血条统一放在坦克上方，使用相同分段、间隔、尺寸、配色和低血警示。没有头顶名称和坦克外框。
- 两种模式共用坦克素材帧、护盾绘制函数，底色、履带节奏、水面动画、像素对齐及树林遮挡图层一致。
- 合作地图与单机均为 624×624 逻辑尺寸，采用同一画布缩放规则。中等宽度页面不再因单机侧栏而压缩战场。

## 联机保护

合作玩家开局有 **3 秒保护**，受击后有 **0.9 秒保护**，使用单机同一张 `protect.png` 和同样的两帧动画。状态栏显示“保护中”。

保护由后端模拟 tick 判定：保护期间子弹仍被消耗，但不扣血；同一个 tick 多颗敌弹最多造成一次伤害。玩家仍能正常移动、开火。敌军没有此保护，基地也不会因此无敌。

新增 `TankState.shieldUntilTick` 和响应字段 `tank.shieldUntilTick`。每步 50ms，开局保护结束于第 60 tick，受击时结束期限设为当前 tick + 18。重连沿用原期限，不重复赠送保护；重新开局重新获得保护，阵亡或结算清除。

## 关键文件

| 文件 | 职责 |
| --- | --- |
| `frontend/src/components/BattleHud.vue` | 右上角血量、得分及左侧关卡状态 |
| `frontend/src/components/BattleFrame.vue` | 共用战场布局、尺寸和操作栏 |
| `frontend/src/game/battleHud.ts` | 两种模式共用的状态栏数据结构 |
| `frontend/src/game/rendering/tankRenderer.ts` | 坦克素材、上方血条、护盾和统一配色 |
| `frontend/src/views/SinglePlayerGameView.vue` | 把本地游戏状态提供给共同状态栏 |
| `frontend/src/views/GameView.vue` | 从权威快照提供双方血量、合作总分和剩余时间 |
| `frontend/src/components/GameBattleCanvas.vue` | 联机动画、补帧及保护绘制 |
| `frontend/src/game/singleplayer/renderer.ts` | 单机复用相同绘制函数 |
| `frontend/src/game/network/protocol.ts` | 校验保护字段，兼容旧响应缺少该字段时按无保护显示 |
| `backend/src/main/java/com/syf/blog/game/runtime/TankState.java` | 保存服务端保护期限 |
| `backend/src/main/java/com/syf/blog/dto/game/play/TankResponse.java` | 向两端同步保护期限 |
| `backend/src/main/java/com/syf/blog/game/engine/GameCoopEngine.java` | 开局保护初始化 |
| `backend/src/main/java/com/syf/blog/game/engine/GameCombatEngine.java` | 保护期间吸收子弹及受击保护 |
| `backend/src/main/java/com/syf/blog/service/game/GameRoomService.java` | 对局结束、退出或断线中止时清理保护 |

## 验证

- 后端游戏相关测试 **141 项通过**，包括保护期限边界、同 tick 多弹、保护中操作、PVP 兼容、重连、重开和结算清理；真实 STOMP 组额外校验保护 JSON 字段，7 项通过。
- 前端测试 **25 项通过**，包含保护字段解析与旧响应兼容；Vue/TypeScript 检查、生产构建和相关文件 ESLint 通过。
- 隔离 Chrome 检查通过：两种模式右上角状态栏、血条均在坦克上方、实际绘制保护 PNG、保护到期停止绘制、无头顶名称和外框、无运行异常。
- 实际操作联机射击并击毁敌军后，两端右上角团队分数均按服务端击毁数同步增加，每辆 100 分。
- 实测两种模式在 1440px 和 900px 视口的画布都为 624px；390px 视口都为 338px，无横向溢出。
- 已查看桌面与窄屏实际截图，保存在 `backend/target/unified-*-preview.png`，仅为本地验证产物。
- 完整双账号流程回归通过：菜单、登录回跳、创建/加入/准备/开局、移动射击、相同结算、重开、自动重连、15 秒断线清理和退出，无浏览器运行异常。

测试身份、地图和房间均在隔离测试服务中，不修改开发数据库。

## 手动验收

本次包含后端规则变化，**先重启你现有的后端，再刷新两端网页**。在原后端终端按 `Ctrl+C`，随后从 `backend` 目录启动：

```powershell
.\mvnw.cmd spring-boot:run
```

1. 单机和合作分别开局，确认右上角显示血量与得分，坦克血条都在上方。
2. 观察开局 3 秒护盾，确认两种模式的素材、大小和动画一致。
3. 联机受到攻击后，确认扣一次血并出现约 0.9 秒护盾，期间连续命中不重复扣血。
4. 击毁敌军，确认单机分数增加 100、合作两端团队得分同步增加 100。
5. 检查移动、转弯、射击、重开和刷新恢复；重开后血量、分数和开局保护复位。

当前运行的用户后端由用户自己重启；本次只启动了独立端口的临时测试服务。
