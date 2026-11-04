# Requirements — 网页联机坦克对战系统（首版）

采用 Kiro 规范驱动开发（SDD）的 requirements 文档格式：每条需求 = 用户故事 + EARS 验收标准。
EARS 句式：`WHEN <触发> THE SYSTEM SHALL <行为>` / `IF <条件> THEN THE SYSTEM SHALL <行为>` /
`WHILE <状态> THE SYSTEM SHALL <行为>`。

范围：一张地图、两人一房间、移动射击至分出胜负。
角色：`player`（玩家）、`owner`（房主，player 的子集）。

---

## R1 创建房间

**用户故事**
作为 `player`，我想创建一个房间并拿到房间号，以便邀请另一名玩家加入。

**验收标准**
1. WHEN 玩家在大厅点击「创建房间」 THE SYSTEM SHALL 生成 6 位数字房间号并在列表显示该房间。
2. WHEN 房间创建成功 THE SYSTEM SHALL 将创建者置为房主并占用席位 1。
3. IF 玩家未登录 THEN THE SYSTEM SHALL 拒绝创建并返回 `SESSION_EXPIRED`。

---

## R2 加入房间

**用户故事**
作为 `player`，我想凭房间号加入房间，以便与房主开始对战。

**验收标准**
1. WHEN 玩家输入 6 位房间号并点击「加入房间」 THE SYSTEM SHALL 将其放入空闲席位。
2. IF 房间已满 THEN THE SYSTEM SHALL 返回 `ROOM_FULL`，前端显示「房间已满，请换一个房间」。
3. IF 房间号不存在 THEN THE SYSTEM SHALL 提示「房间不存在」且不改变列表状态。
4. WHEN 第二名玩家加入成功 THE SYSTEM SHALL 在 1 秒内同步双方席位信息。

---

## R3 准备与开局

**用户故事**
作为 `owner`，我想在双方准备后开始对局，以便控制开局时机。

**验收标准**
1. WHEN 玩家点击「准备」 THE SYSTEM SHALL 将本人状态置为已准备并同步给对方。
2. WHILE 双方均为已准备 THE SYSTEM SHALL 使房主的「开始游戏」按钮可用。
3. IF 非房主点击开始 THEN THE SYSTEM SHALL 返回 `NOT_OWNER`，前端按钮保持置灰。
4. WHEN 房主开始对局 THE SYSTEM SHALL 向双方下发 `game_start` 并复制地图模板为本局状态。

---

## R4 坦克移动

**用户故事**
作为 `player`，我想用方向键或 WASD 控制坦克移动，以便在对局中走位。

**验收标准**
1. WHEN 玩家按下方向键或 WASD THE SYSTEM SHALL 采集方向并只向服务端发送 `input{dir}`，不上报坐标。
2. WHEN 服务端收到 `input` THE SYSTEM SHALL 按帧推进该坦克坐标，并将坐标钳制在地图边界内。
3. WHEN 服务端更新坐标 THE SYSTEM SHALL 以 10 Hz 向房间双方广播 `game_state`。
4. WHILE 前端收到 `game_state` THE SYSTEM SHALL 在两次快照之间插值渲染，位置误差不超过 2 像素。
5. WHEN 玩家松开按键 THE SYSTEM SHALL 在 100 ms 内停止该坦克移动。
6. WHEN 玩家离开页面 THE SYSTEM SHALL 移除键盘监听并停止动画帧循环。

---

## R5 发射子弹

**用户故事**
作为 `player`，我想按空格发射子弹，以便攻击对方。

**验收标准**
1. WHEN 玩家按下空格 AND 冷却时间已到 THE SYSTEM SHALL 发送 `fire{dir, tankId}`。
2. IF 冷却时间未到 THEN THE SYSTEM SHALL 丢弃本次输入，不产生子弹。
3. WHEN 服务端收到 `fire` THE SYSTEM SHALL 在坦克当前位置按朝向生成一颗子弹。
4. WHILE 子弹存在 THE SYSTEM SHALL 按帧推进其坐标。
5. WHEN 子弹越出地图边界 THE SYSTEM SHALL 销毁该子弹并从双方的 `game_state` 中移除。
6. WHEN 前端收到含 `bullets` 的快照 THE SYSTEM SHALL 绘制全部子弹，双方子弹集合保持一致。

---

## R6 结算显示

**用户故事**
作为 `player`，我想在结束时看到胜负与统计，以便知道本局结果。

**验收标准**
1. WHEN 一方坦克血量归零 THE SYSTEM SHALL 判定胜负并下发 `game_over{winnerId, stats[], durationSec}`。
2. WHEN 前端收到 `game_over` THE SYSTEM SHALL 在 500 ms 内渲染结算页，显示胜负、命中数、承受伤害、用时。
3. IF 前端收到的 `winnerId` 与本机玩家一致 THEN THE SYSTEM SHALL 显示「胜利」，否则显示「失败」。
4. IF 对局因对方断线结束 THEN THE SYSTEM SHALL 显示「对方已离开，本局结束」且不显示胜负。
5. WHILE 当前玩家非房主 THE SYSTEM SHALL 将「再来一局」按钮置灰。
6. WHEN 出现错误 THEN THE SYSTEM SHALL 按错误码映射表显示中文提示，不展示 `code` 与堆栈。

---

## R7 非功能要求

1. WHEN 玩家产生输入 THE SYSTEM SHALL 在 100 ms 内反映到本机画面（本地环境）。
2. WHILE 战斗进行中 THE SYSTEM SHALL 保持画面帧率不低于 30 fps。
3. IF 一方连接中断 THEN THE SYSTEM SHALL 在 3 秒内通知另一方。
4. THE SYSTEM SHALL 在 Chrome 与 Edge 最近两个大版本上正常运行。

---

## 不在本期范围

排行榜、匹配队列、AI 敌人、道具、多地图编辑器、移动端触控、聊天、观战。
