# Design — 网页联机坦克对战系统（首版）

对应 `requirements.md`。技术栈：Vue 3 + TypeScript + Vite + Canvas 2D（前端）、
Spring Boot + Java 17 + JPA + MySQL（后端）、WebSocket `/ws/game`（实时通信）。

---

## 1 架构

```
浏览器（Vue 3 + Canvas）
  ├─ HTTP /api ────────────► 博客后端（Spring Boot）
  │                              └─ 地图查询 ──► MySQL
  └─ WebSocket /ws/game ───► 游戏服务
                                 ├─ 房间管理（RoomService）
                                 ├─ 战局推进（GameLoop）
                                 ├─ 碰撞与伤害（CollisionDetector）
                                 └─ 结算（GameOverService）
                                        └─ 对局记录 ──► MySQL
```

分层原则：浏览器只做输入采集与渲染；坐标、子弹、伤害、胜负全部在服务端计算。
前端不得自行判定命中或胜负。

---

## 2 组件与职责

| 组件 | 层 | 职责 | 负责人 |
|---|---|---|---|
| `LobbyView.vue` | 前端 | 创建/加入房间、房间列表 | 史义峰 |
| `RoomView.vue` | 前端 | 席位、准备、开局按钮 | 史义峰 |
| `KeyboardInput` | 前端 | 采集方向键/WASD/空格，输出方向事件 | 汪子骏 |
| `playerMovement` | 前端 | 位置插值渲染 | 汪子骏 |
| `playRenderer` | 前端 | 绘制地图、坦克、子弹 | 汪子骏 |
| `ResultView.vue` | 前端 | 结算页、错误提示 | 汪子骏 |
| `gameSocket` | 前端 | 连接、重连、消息分发 | 史义峰 |
| `RoomService` | 后端 | 房间创建、加入、准备、释放 | 史义峰 |
| `GameLoop` | 后端 | 按帧推进坦克与子弹，10 Hz 广播 | 史义峰 |
| `CollisionDetector` | 后端 | 子弹与坦克碰撞、伤害计算 | 史义峰 |
| `GameOverService` | 后端 | 判定胜负、生成统计 | 史义峰 |
| `Tank` / `Bullet` / `GameState` | 共享模型 | 战局数据结构 | 汪子骏（字段约定） |

---

## 3 数据模型

| 类 | 字段 | 说明 |
|---|---|---|
| `Tank` | id, x, y, dir, hp | 坐标为地图格坐标，dir ∈ {up,down,left,right} |
| `Bullet` | id, x, y, dir, ownerId | 越界即销毁 |
| `GameState` | tanks[], bullets[], remainSec | 每秒 10 次快照 |
| `GameOver` | winnerId, stats[], durationSec | stats 含 hits、damage |
| `Room` | roomId, ownerId, members[], status | status ∈ {waiting, playing, closed} |

持久化：`game_map`（地图模板）、`game_log`（对局结果）。战局状态为内存态，不落库。

---

## 4 消息接口

| 消息 | 方向 | 字段 |
|---|---|---|
| `create_room` | C→S | — |
| `join_room` | C→S | roomId |
| `ready` | C→S | ready: boolean |
| `game_start` | S→C | roomId, players[] |
| `input` | C→S | dir |
| `fire` | C→S | dir, tankId |
| `game_state` | S→C | tanks[], bullets[], remainSec |
| `game_event` | S→C | type, actorId, targetId |
| `game_over` | S→C | winnerId, stats[], durationSec |
| `error` | S→C | code, message |

握手阶段由服务端从 Session 解析登录用户；消息入口只做解析、校验与分发，资源权限由 `RoomService` 检查。

---

## 5 关键流程（移动）

1. `KeyboardInput` 采集方向，输出 `input{dir}`。
2. `gameSocket` 发送 WebSocket 帧。
3. `GameLoop` 按 60 Hz 推进：读取该坦克当前方向，计算新坐标，调用 `clampToMap()` 钳制边界。
4. 每 100 ms 打包 `game_state` 广播给房间双方。
5. 前端 `playerMovement` 在两次快照之间线性插值，渲染到 Canvas。

设计要点：前端发方向、不发坐标。这样两边看到的战局由同一份服务端状态驱动。

---

## 6 错误处理

| code | 触发场景 | 前端表现 |
|---|---|---|
| `SESSION_EXPIRED` | Session 失效 | 「登录已过期，请重新登录」 |
| `ROOM_FULL` | 第三人加入 | 「房间已满，请换一个房间」 |
| `NOT_OWNER` | 非房主开局 | 「只有房主可以开始游戏」 |
| `GAME_NOT_RUNNING` | 未开局发送输入 | 「对局尚未开始」，丢弃输入 |
| `PEER_DISCONNECTED` | 对方断线 | 「对方已离开，本局结束」 |

前端维护 code → 文案映射表，服务端不下发中文，改文案不动后端。
所有提示不得暴露异常堆栈。

---

## 7 性能与可靠性

- 广播频率 10 Hz，前端插值到 60 fps，避免带宽随帧率线性上升。
- 快照只传变化实体（增量）作为二期优化，首版传全量。
- 断线检测基于心跳超时 3 秒；超时后关闭房间并通知另一方。
- 一局结束释放房间对象，防止内存泄漏。

---

## 8 测试策略

| 层级 | 内容 | 工具 |
|---|---|---|
| 单元 | 边界钳制、子弹越界、错误码映射 | JUnit / Vitest |
| 集成 | 房间创建到开局的消息流 | Spring Boot Test |
| 端到端 | 双浏览器跑通一局 | 手工 + 录屏 |
| 性能 | 输入延迟、帧率、结算时延 | DevTools Performance |

---

## 9 边界与不做

- 不做服务端权威物理（重力、惯性），只做格子级移动与直线子弹。
- 不做断线重连后的战局恢复，断线即判本局结束。
- 不做反作弊以外的额外校验（首版信任 Session）。
