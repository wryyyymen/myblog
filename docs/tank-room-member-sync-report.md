# 阶段报告：房间成员实时同步

日期：2026-11-18。状态：代码已实现，等待用户手动验收。

## 本阶段目标

在现有创建、加入房间功能上，让房主和加入者实时看到相同的成员名单。按用户要求，每完成一个阶段就停下来手动验收，通过后再进入下一阶段。

本次开始时，工作区已有创建/加入房间、通知器和测试页的未提交代码。本阶段在这些代码上继续实现；原有机器人测试文件的删除和其他无关改动均保留原样。

## 实现结果与文件

| 文件 | 本阶段改动 |
| --- | --- |
| `backend/src/main/java/com/syf/blog/controller/GameMessageController.java` | 创建或加入成功后调用通知器；继续保留 `room:created`、`room:joined` 请求回复 |
| `backend/src/main/java/com/syf/blog/websocket/game/GameRoomNotifier.java` | 通过服务端成员名单向每个成员推送 `room:updated` |
| `backend/src/main/java/com/syf/blog/game/runtime/GameRoom.java` | 保存从 1 开始的房间版本号 |
| `backend/src/main/java/com/syf/blog/service/game/GameRoomService.java` | 在同步方法内让真实加入递增版本，响应包含同一时刻的版本与成员副本；重复请求不增加版本 |
| `backend/src/main/java/com/syf/blog/dto/game/room/RoomResponse.java` | 在原有字段基础上增加 `version` |
| `backend/src/main/java/com/syf/blog/websocket/game/GameMessageChannelInterceptor.java` | 限制客户端可发送和订阅的目标，阻止绕过 Controller 伪造代理消息 |
| `backend/src/main/java/com/syf/blog/config/GameWebSocketConfig.java` | 注册入站目标校验器 |
| `frontend/src/views/WebSocketTestView.vue` | 接收三种房间消息，展示人数、成员、房主和本人；忽略同房间旧版本快照 |

本阶段未增加数据库表、依赖或新基础设施，房间仍然保存在后端内存中。

## 消息契约

连接仍为 `/ws/tankgame`，客户端仍订阅 `/user/queue/replies`。

| 操作 | 客户端发送目标 | 请求正文 | 当前连接确认 | 房间成员通知 |
| --- | --- | --- | --- | --- |
| 创建或取回当前房间 | `/app/room/create` | `{}` | `room:created` | `room:updated` |
| 加入房间 | `/app/room/join` | `{"roomId":"房间唯一编号"}` | `room:joined` | `room:updated` |
| 测试消息 | `/app/ping` | `{"text":"hello"}` | `pong` | 无 |

成功的房间消息示例：

```json
{
  "type": "room:updated",
  "payload": {
    "roomId": "示例房间UUID",
    "ownerUserId": 14,
    "memberUserIds": [14, 15],
    "version": 2
  }
}
```

初次创建为版本 1，第二位玩家首次加入变为版本 2。重复创建/加入返回当前版本。前端只比较同一房间的版本；相同版本可以重复到达，较旧版本不会覆盖当前名单。请求确认和成员通知都可能到达，因此同一操作看到多条成功消息是预期行为。

入站 `SEND` 只允许上表三个目标，`SUBSCRIBE` 只允许 `/user/queue/replies`，两者都要求连接已绑定身份。客户端冒用服务端专属的 `MESSAGE` 等指令同样被拒绝，避免绕过目标校验。普通业务失败仍返回 `type=error`；目标越权属于协议层错误，测试页会关闭连接。以后新增业务目标时需要同步更新白名单。

## 调用链与数据存放位置

1. 用户在测试页点击创建或加入，STOMP 客户端发送 JSON 正文。
2. 入站校验器检查连接身份和消息目标，随后 Spring 按 `/app` 前缀路由到 `GameMessageController` 的对应方法。
3. Controller 从握手时绑定的 `Principal.getName()` 读取可信用户 ID，请求正文只提供目标房间号。
4. `GameRoomService` 在同步方法中读取/修改 `rooms` 和 `userRoomIds` 两个内存 Map，检查房间存在、跨房间限制和两人上限，再复制成员名单及版本作为响应。
5. Controller 将快照交给 `GameRoomNotifier`。通知器逐个调用 `convertAndSendToUser(用户ID字符串, "/queue/replies", 通知)`，由 Spring 的用户目标解析器送到该账号的回复订阅。
6. 前端订阅回调进入 `handleServerMessage()`，检查字段、成员与版本后更新 `currentRoom`，Vue 更新成员列表。

这里的用户定向推送会送达同一账号的所有有效 STOMP 连接，不承诺只保留一个连接。退出页面/断开时前端清理连接和显示；后端房间当前仅在进程重启时整体清空，退出/掉线回收将在后续阶段实现。

Lombok 构造方法说明：`GameMessageController` 接收 `GameRoomService`、`GameRoomNotifier`；`GameRoomNotifier` 接收 `SimpMessagingTemplate`；`GameRoom` 仍接收 `roomId`、`ownerUserId`，版本使用字段初值；`RoomResponse` 依次接收 `roomId`、`ownerUserId`、`memberUserIds`、`version`。

## 基础检查

| 检查 | 结果 |
| --- | --- |
| 房间业务与通知调用测试 | 12 项通过，覆盖版本递增、重复请求、快照隔离、定向通知、满员和不存在房间 |
| 消息权限测试 | 14 项通过，覆盖允许目标、越权发送/订阅、无身份、控制帧、心跳、服务端指令冒用及异常信息保护 |
| 前端 `npm run build` | 通过，包含 Vue/TypeScript 类型检查和生产构建 |
| 测试页 ESLint | 通过 |
| `git diff --check` | 通过 |
| 双浏览器真实联调 | 等待用户按下表手动验收 |

后端本阶段测试合计 26 项通过，0 失败、0 错误、0 跳过。基础检查直接创建内存服务并模拟消息发送组件，不启动应用上下文、不连接或修改开发数据库；未运行依赖真实数据库的全量上下文测试。复现命令：

```powershell
cd D:\code\springboot\blog-ai\backend
.\mvnw.cmd test "-Dtest=GameRoomServiceTest,GameMessageControllerTest,GameMessageChannelInterceptorTest" "-Dspring.jpa.hibernate.ddl-auto=validate" "-Dspring.sql.init.mode=never" "-Ddebug=false"
```

前端构建时使用了本机已有依赖，未安装或升级工具链。实际 Node.js 为 `v22.12.0`，本次构建通过，但低于 `frontend/package.json` 声明的 `^22.18.0 || >=24.12.0`；后续环境准备应遵循项目声明。

## 手动验收步骤

### 准备

1. 停止原项目 `D:\code\springboot\blog\backend` 的后端，再启动下面的 `blog-ai` 后端以加载本阶段代码。保持已有本地数据库配置。两份项目默认都使用 8081，不能同时占用该端口；只重启原项目不会加载本阶段代码。

   ```powershell
   cd D:\code\springboot\blog-ai\backend
   .\mvnw.cmd spring-boot:run
   ```

2. 在另一个终端启动前端，使用终端实际输出的地址：

   ```powershell
   cd D:\code\springboot\blog-ai\frontend
   npm run dev
   ```

3. 准备两个独立浏览器环境，例如 Chrome 普通窗口与 Edge，或普通窗口与无痕窗口。分别登录账号 A、B，并进入 `/game/ws-test`。同一浏览器的两个普通窗口会共享登录 Cookie，不能当成两个独立账号环境。
4. 两边点击“连接”，等待显示“已连接”。B 不要先点击创建房间，因为当前还没有退出原房间功能。

### 验收表

| 编号 | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | A 创建房间 | 显示房间号、A 为房主、1/2 人；日志中 `version` 为 1 |
| 2 | B 粘贴 A 的房间号并加入 | A 无须刷新或点击任何按钮，自动变为 2/2 人；两边房间号、房主、成员一致，日志版本为 2 |
| 3 | 检查两边的成员标记 | A、B 各自正确标出本人，只有 A 标为房主 |
| 4 | A 再次创建，B 再次加入同房间 | 仍是原房间与两名成员，成员不重复，版本仍为 2 |
| 5 | 在第三个独立浏览器环境登录 C，尝试加入 | C 收到“房间已满”业务错误，A/B 名单与版本不变 |
| 6 | C 加入一个不存在的房间号 | C 收到“房间不存在”，不会污染已有房间 |
| 7 | 任意已连接页面发送 `hello`，再发送空文本 | 分别收到 `pong` 与参数校验 `error`，连接仍可正常使用 |
| 8 | B 断开后重新连接，再点击创建房间 | 取回此前所在的房间；本阶段断开不会移除 B 的后端席位 |

第二人加入时，后端预期关键输出（用户 ID 和房间号以实际为准）：

```text
[GameMessageController.joinRoom] userId = 15，roomId = ...
[GameRoomService.joinRoom] 加入成功，当前成员 = [14, 15]
[GameRoomNotifier.notifyRoomUpdated] roomId = ...，通知成员 = [14, 15]
[GameRoomNotifier.notifyRoomUpdated] 提交通知，userName = 14，destination = /queue/replies
[GameRoomNotifier.notifyRoomUpdated] 提交通知，userName = 15，destination = /queue/replies
```

测试失败时，请提供失败编号、两边页面显示、测试页最近的消息日志和后端对应输出；不要提供 Cookie、令牌或完整会话 ID。

## 首次手测问题记录

2026-11-18，用户收到 `room:created`，但 `payload` 缺少 `version`，页面拒绝更新。检查监听进程后确认：5173 上的 Vite 来自 `D:\code\springboot\blog-ai\frontend`，8081 上的 Java 类路径却是 `D:\code\springboot\blog\backend\target\classes`。实际连接的是原项目后端，未加载本阶段通知与版本代码。

处理方式：在 IDE 停止原项目后端，从本文指定的 `blog-ai\backend` 启动后端，刷新前端后重新登录、连接并创建房间。预期收到含 `"version":1` 的房间信息，以及 `room:updated` 通知。该问题不通过放宽前端字段校验解决；手动联调仍待用户重试确认。

用户再次重启后仍复现，进一步确认新进程 31536 依旧加载原项目目录。随后已停止该旧进程，并由助手从 `blog-ai\backend` 启动正确副本。08:44:18 的启动日志确认实际加载 `D:\code\springboot\blog-ai\backend\target\classes`，监听 8081，进程为 30088；无登录 Cookie 请求 `/api/auth/me` 正常返回 401。启动使用 `ddl-auto=validate`、`spring.sql.init.mode=never` 和 `debug=false`，未执行数据库结构更新或初始化脚本。

此时后端已在后台运行，用户可直接刷新页面、重新登录并继续手测，无需再次从 IDEA 启动。后台输出在 `backend/target/backend-manual-test.log`（构建目录已被 Git 忽略）。如果以后改用 IDEA 启动，需要从 `D:\code\springboot\blog-ai\backend\pom.xml` 打开副本，并确认启动日志中的路径确实包含 `blog-ai`。创建、加入的真实浏览器验收仍待用户反馈。

## 后续阶段

本阶段手测通过后，再做准备/取消准备、主动退出与房主转移。游戏开始、地图加载、权威战局和断线恢复按后续阶段分别实现。目前尚不能进行双人实时战斗，也尚未实现服务端对已建立 STOMP 连接的登出撤销和 Session 失效处理。
