# 坦克菜单与双人合作 PVE 验收报告

日期：2026-11-18。实现和自动验证已完成，等待用户手动验收。

画面流畅度及移除边框、头顶名称的后续调整，见 [画面调整报告](tank-rendering-report.md)。

最新的共用状态栏、上方血条及联机保护，见 [UI 与保护统一报告](tank-unified-ui-report.md)。

2026-11-19 新增的结束结算页、可加入房间列表和游戏声音，见 [结算、房间列表与声音报告](tank-results-rooms-audio-report.md)。

## 试玩入口

- 菜单：<http://localhost:5173/game>
- 单人 PVE：<http://localhost:5173/game/single>，无需登录。
- 双人合作 PVE：<http://localhost:5173/game/online>，使用两个不同的博客账号。

当前开发前端监听 `0.0.0.0:5173`，后端使用 `8081`。另一台电脑在同一局域网时，访问运行电脑的局域网 IP 和端口 5173，两人必须连接同一台后端。跨公网部署需使用站点地址；本次没有发布到公网。

同一浏览器的普通窗口共享 Cookie。单机验收请使用两个浏览器，或者普通窗口与无痕窗口，分别登录不同账号。

## 本次完成的行为

| 模块 | 实现结果 |
| --- | --- |
| 菜单 | 单人闯关、双人合作、操作说明、关卡预览、返回菜单 |
| 素材 | 复用 `public/resources/png` 的 Logo、坦克、地形、基地、子弹、爆炸图片；己方绿色、队友黄色、敌军红色 |
| 登录与大厅 | 自动建立 STOMP 连接；未登录可进入登录页并返回邀请地址；分享房间号或邀请链接 |
| 房间 | 最多两人，准备/取消准备，房主开局，双方加载完素材后开始；显示在线状态和血量 |
| 合作规则 | 同一局 AI、基地和地图；队友子弹穿过队友且不扣血，也不会伤害基地 |
| 敌军 | 共 8 辆，最多同时存活 3 辆，从顶部每隔至少 2.5 秒出兵；寻路、绕钢墙/河流、破砖、瞄准玩家或基地 |
| 战斗 | 玩家 3 血、敌军 1 血；WASD/方向键移动、空格连续开火；扫掠碰撞防穿墙，砖墙可击毁 |
| 共同结算 | 全歼 8 辆敌军共同胜利；基地被毁、两人都阵亡或 3 分钟内未清敌则共同失败；一人阵亡可以观战 |
| 重开 | 房主点击“再来一局”，双方重新准备；新 matchId，地图、血量、基地、敌军、子弹和输入全部复位 |
| 重连 | 自动重试最多 5 次；离线立即停止该玩家输入，席位保留 15 秒，期间战斗继续；恢复后同步同一局 |
| 退出与清理 | 主动退出或断线超时会中止进行中的合作，不为队友判 PVP 胜利；转移房主、释放席位，空房回收 |

联机没有全局暂停；切换窗口只停止本机操作，服务器仍推进游戏。冰面暂按普通可通行地面处理。当前是一张基础合作关卡，没有道具、升级、选关和匹配大厅。房间保存在当前后端进程内，重启服务器后需要重新创建。

## 后端运行调用链

1. `GameMessageController.createCoopRoom` 接收 `/app/room/create-coop`。用户 ID 来自已登录连接的 `Principal`，房间由 `GameRoomService` 存入 `rooms`、`userRoomIds`。响应包含 `version` 和 `mode: COOP`。
2. 加入和准备仍使用 `/app/room/join`、`/app/player/ready`。`GameMatchService.start` 请求 `beginStart`，检查房主、两名在线且已准备成员，分配 matchId 并进入 `LOADING`。
3. 合作模式使用 `GameCoopEngine` 的内置 26×26 地图，不依赖数据库中演示地图的尺寸。`installMap` 保存本局地图副本、两名玩家和基地，发送 `game:init`。
4. 前端加载地图和 PNG 后发送 `/app/game/loaded`。两名玩家都确认后进入 `PLAYING`，双方收到相同的 `game:start`。
5. 前端只发送输入序号、方向和开火状态，不提交坐标或伤害。`GameRoomService.submitInput` 检查成员、对局号和输入序号，只保存最新输入。
6. `GameMatchService.tick` 每次按 50ms 步长推进：`GameCoopEngine.step` 决策 AI；`GameMovementEngine.step` 移动人类；`GameCombatEngine.step` 生成子弹、检测最近碰撞、统一扣血并判断共同胜负。每两步推送一次 `game:state`，结束只推送一次 `game:end`。
7. `GameBattleCanvas` 对两份已知坐标做插值并绘制素材。己方和队友仍是两个用户坦克，AI 使用负数内部身份；前端不能通过输入接口控制 AI。
8. `GamePresenceListener` 按用户统计连接，只有最后一条连接关闭才进入离线；超过 15 秒由 `advanceGames` 清理。刷新或短暂重连使用 `/app/room/current`、`/app/game/sync` 获取现有房间和地图。

主要新增类：`GameCoopEngine`、`BaseState`、`EnemyBrain`、`EnemyResponse`、`BaseResponse`。`BaseState` 的 Lombok 构造参数依次为 `x、y、size`；`EnemyBrain` 无必填构造参数。响应 DTO 使用 Lombok 按字段顺序生成全参数构造方法。固定身份和尺寸保持 `final`，可变运行字段才提供 setter。

旧 `/app/room/create` 的 PVP 行为保留兼容；新菜单创建的是合作房间。创建合作房间时若账号仍有旧 PVP 房间，会要求先退出旧房间。新增快照字段 `mode、enemies、base、enemyTotal、enemySpawned、enemyDefeated、teamResult` 已同步前端解析。

## 验证结果

- 后端游戏相关测试 **141 项通过**，零失败、零错误、零跳过，包含合作保护规则的新增验证。
- 前端 **25 项通过**：12 项单人规则测试，7 项网络协议解析测试，6 项运动补帧测试。
- `npm run build`（Vue/TypeScript 检查及生产构建）通过；改动页面和游戏文件 ESLint 通过。
- 隔离 Chrome 双账号浏览器流程通过：菜单、单人入口、登录回跳、自动连接、创建/加入/准备/开局、实际方向键和空格射击、相同 tick 的战场逐字段一致、真实 AI 导致双方相同失败结果、重开复位、自动重连、15 秒离线清理、退出。无浏览器运行异常。
- 已查看菜单、合作战场和结算截图。本地检查产物位于 `backend/target/*-preview.png`，不提交 Git。
- 当前 Vite 开发服务的 localhost 菜单和单人素材加载检查通过；390px 宽菜单没有横向溢出。`/game`、`/game/single`、`/game/online` 均返回 200，未登录 `/api/auth/me` 正常返回 401。
- 测试用身份和内置地图仅位于 `src/test` 的 `GameBrowserTestServer`，不进入生产包，不读写开发数据库。
- `deploy/nginx/myblog.conf` 已补 `/ws/` 升级代理、75 秒超时和关闭缓冲；本机未安装 Nginx，未执行线上部署或 `nginx -t`。

复现规则测试和构建：

```powershell
cd D:\code\springboot\blog-ai\backend
.\mvnw.cmd test "-Dtest=Game*Test,com.syf.blog.dto.game.room.RoomRequestValidationTest" "-Dspring.jpa.hibernate.ddl-auto=validate" "-Dspring.sql.init.mode=never" "-Ddebug=false"

cd ..\frontend
npm run test:game
npm run build
```

`frontend/scripts/test-online-browser.mjs` 可复现浏览器流程：需先构建前端，在 18082 启动 `GameBrowserTestServer`，在 9226 启动使用独立用户目录的 Headless Chrome。测试启动命令为 `mvnw.cmd spring-boot:test-run -Dspring-boot.run.main-class=com.syf.blog.websocket.game.GameBrowserTestServer`。该脚本会关闭它连接的测试 Chrome，不能连接日常使用的浏览器。

## 请按阶段手动验收

### 阶段一：菜单和单人入口

1. 打开 `/game`，检查菜单、关卡预览、操作说明是否正常。
2. 点击“单人闯关”，无需登录即可开始，确认移动、射击和返回菜单正常。

### 阶段二：双人房间

1. 两个独立浏览器登录不同账号，进入“双人合作”。页面应自动连接，不能出现 version 错误。
2. A 创建房间，B 粘贴房间号或打开邀请链接加入。两端应显示同一房间、两名在线成员。
3. 双方准备，A 开始；没有准备齐时不允许开始。

### 阶段三：合作战斗

1. 点击战场，使用 WASD/方向键和空格，确认两端能看到彼此移动和子弹。
2. 对队友射击，队友不应扣血；撞砖、钢、河流和坦克时不能穿过。砖可击毁，钢不可击毁。
3. 观察红色 AI 出兵、移动和攻击，两端的击毁数量、基地和血量应一致。
4. 一人阵亡后观察队友继续战斗；基地被毁或全员阵亡时，两人都显示失败。
5. 正常游玩清除 8 辆敌军应共同胜利。房主再来一局，重新准备后所有战斗状态复位。

### 阶段四：恢复与退出

1. 游戏中刷新 B，15 秒内恢复应仍是原房间、原 matchId、原血量和敌军进度。
2. 关闭 B 并等待超过 15 秒，A 应看到合作中止、B 席位释放、房间回到等待。
3. 主动退出时立即释放席位；房主退出后剩余成员成为房主，最后一人退出回收房间。

完成后请反馈失败阶段、操作过程以及两端日志，继续按阶段修正。
