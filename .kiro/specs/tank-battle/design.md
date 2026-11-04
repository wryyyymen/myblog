# 设计文档：坦克大战网页联机版

## 架构概览

游戏作为现有博客系统的一个模块存在，不单独部署。前端复用博客的 Vue 3 工程，后端复用 Spring Boot 进程，地图模板继续存放在 MySQL。

```
浏览器 (Vue 3 + Canvas)
  ├─ HTTP  /api/game/maps/{id}   读取地图模板
  └─ WS    /ws/game              房间、输入、战局状态
        │
Spring Boot (8081)
  ├─ controller  接收 HTTP 请求，参数校验
  ├─ service     业务规则，事务
  ├─ repository  JPA 数据访问
  └─ game        内存中的房间与战局状态（不落库）
        │
MySQL  game_map 表（地图模板）
```

**关键取舍**：房间、坦克、子弹等运行时状态全部放在内存中，不写数据库。理由是对局生命周期只有几分钟，重启后重开一局即可，持久化带不来收益，反而增加复杂度。地图模板是静态数据，继续存库。

## 前端组件

| 模块 | 文件 | 职责 |
|---|---|---|
| 页面 | `src/views/GameView.vue` | 初始化画布、请求地图、驱动渲染循环 |
| 键盘输入 | `src/game/keyboardInput.ts` | 把按键翻译成方向 0~3，处理多键优先级和失焦 |
| 移动计算 | `src/game/playerMovement.ts` | 纯函数，根据方向、时间、边界算出新坐标 |
| 地图加载 | `src/game/mapLoader.ts` | 加载材质图片，逐格绘制到离屏画布 |
| 坦克绘制 | `src/game/playRenderer.ts` | 按朝向从精灵图切帧绘制 |
| 资源地址 | `src/game/assets.ts` | 材质编号到图片路径的映射 |

**离屏画布的作用**：地图是静态的，40×26 共 1040 个格子，每帧重画一遍会浪费性能。初始化时画一次存到离屏 canvas，之后每帧只做一次 `drawImage` 复制。

**纯函数约束**：`playerMovement.ts` 的 `movePlayer()` 只接收参数、返回新坐标，不读 DOM、不访问 `window`。原因是接入联机后这段逻辑要搬到服务端，写成纯函数才能逐行翻译。

## 后端组件

| 层 | 类 | 职责 |
|---|---|---|
| 控制器 | `GameMapController` | `GET /api/game/maps/{id}`，允许匿名访问 |
| 服务 | `GameMapService` | 查询地图，找不到抛 `BusinessException(404)` |
| 仓储 | `GameMapRepository` | 继承 `JpaRepository<GameMap, Long>` |
| 实体 | `GameMap` | 映射 `game_map` 表，`seed` 字段用 JSON 类型存二维数组 |
| DTO | `GameMapResponse` | record，把实体转成接口响应，不泄露内部结构 |

规划中但尚未实现的部分（见 `docs/tank-room-websocket-design.md`）：

| 类 | 职责 |
|---|---|
| `GameWebSocketConfig` | 注册 `/ws/game` 端点和握手拦截器 |
| `GameWebSocketHandler` | 解析消息、按 type 分发、处理连接关闭 |
| `GameRoomService` | 建房、进房、准备、开局、退出 |
| `GameEngine` | 移动、碰撞、开火、伤害、胜负判定 |
| `GameLoopService` | 固定步长推进所有房间，生成状态快照 |

## 数据模型

### 持久化实体

```java
@Entity @Table(name = "game_map")
class GameMap {
    Long id;
    String name;
    Integer tileSize;          // 格子边长，当前 24
    List<List<Integer>> seed;  // 二维材质矩阵，JSON 列
}
```

### 内存对象（规划）

| 对象 | 主要字段 |
|---|---|
| `GameRoom` | roomId、roomCode、ownerUserId、status、members |
| `RoomMember` | userId、slot、ready、connected |
| `GameMatch` | matchId、tick、tanks、bullets、destroyedCells、result |
| `TankState` | userId、x、y、direction、hp、alive、fireCooldown |
| `BulletState` | bulletId、ownerUserId、x、y、direction |

## 接口设计

### HTTP

```
GET /api/game/maps/{id}
→ 200 { id, name, tileSize, seed: [[1,1,...],[...]] }
→ 400 地图 ID 不大于 0
→ 404 地图不存在
```

### WebSocket 消息

统一外层结构：

```json
{ "type": "player:input", "requestId": "req-1001", "payload": { ... } }
```

| 方向 | type | 说明 |
|---|---|---|
| 客户端→服务端 | `room:create` / `room:join` | 建房、进房 |
| 客户端→服务端 | `player:ready` | 设置准备状态 |
| 客户端→服务端 | `room:start` | 房主发起开局 |
| 客户端→服务端 | `player:input` | 上报方向与开火状态 |
| 服务端→客户端 | `room:state` | 房间成员与准备状态 |
| 服务端→客户端 | `game:init` | 开局地图副本与出生点 |
| 服务端→客户端 | `game:state` | 每 100ms 推送的战局快照 |
| 服务端→客户端 | `game:end` | 结算结果 |

**方向编码沿用前端约定**：0 上、1 下、2 左、3 右、null 停止。前后端共用同一套编号，避免转换出错。

## 关键流程

### 加载地图

```
GameView.onMounted
  → fetch /api/game/maps/1
  → drawMap(mapContext, map)     逐格绘制到离屏画布
  → createPlayerRenderer()       加载坦克精灵图
  → requestAnimationFrame(renderScene)
```

### 渲染循环

```
每帧:
  dt = 当前时间戳 - 上次时间戳
  if 有方向输入: player.x/y = movePlayer(player, direction, dt, bounds)
  clearRect
  drawImage(离屏地图)
  drawImage(坦克精灵帧)
  预约下一帧
```

### 接入联机后的变化

```
每帧:
  读取服务端最新快照，插值计算显示位置
  绘制两辆坦克、子弹、血量
输入变化时:
  发送 player:input，不直接改坐标
```

**这一处替换是联机改造的核心**：本地的 `movePlayer` 调用换成“发输入、读快照”，绘制代码几乎不动。

## 错误处理

| 场景 | 处理 |
|---|---|
| 地图请求失败 | 前端显示错误提示；开发阶段可退回本地地图，保证移动逻辑可独立验证 |
| 地图材质编号未知 | `mapLoader.drawMap` 抛出明确错误，指出是哪个编号 |
| 素材图片加载失败 | `loadImage` 清除缓存并 reject，允许重试 |
| 房间不存在 / 已满 | 服务端返回 `error` 消息，code 为 `ROOM_NOT_FOUND` / `ROOM_FULL` |
| 非房主发起开局 | 返回 `NOT_ROOM_OWNER` |
| 连接断开 | 保留席位 15 秒，超时判负 |

HTTP 层统一由 `GlobalExceptionHandler` 把 `BusinessException` 转成 `{ "message": "..." }`，状态码取自异常对象。

## 测试策略

| 层次 | 方式 | 覆盖内容 |
|---|---|---|
| 移动逻辑 | 纯函数单元测试 | 四方向位移、dt 换算、边界钳制、不修改入参 |
| 碰撞逻辑 | 纯函数单元测试 | 四角判定、砖墙/钢墙/河流阻挡、树林/冰面通行 |
| 地图接口 | MockMvc | 正常返回、404、非法 ID |
| WebSocket | 隔离上下文集成测试 | 连接建立、消息收发、关闭清理 |
| 联机对战 | 手工双浏览器 | 两端画面一致、断线重连 |

单元测试优先。规则算法写成纯函数，不依赖 Spring 上下文，跑得快也容易定位问题。
