# 阶段报告：准备、主动退出与房主转移

日期：2026-11-18。状态：代码与基础检查完成，等待用户手动验收。

## 本阶段行为

- 成员可以准备和取消准备，只能修改自己的状态。
- 重复设置相同准备状态成功返回，房间版本不增加。
- 有新成员加入或成员主动退出时，所有准备状态清空，剩余成员重新确认准备；重复加入不清空准备。
- 主动退出立即释放该用户的席位和房间关联，之后可以创建或加入别的房间。
- 房主退出时，剩余成员成为房主；最后一人退出时，删除房间。
- 所有实际状态变化都增加 `version`，继续通过 `room:updated` 同步成员。
- 同账号其他标签页收到 `room:left` 后，也清除对应旧房间。前端忽略退出前的旧快照，迟到的退出确认不能清掉不同房间或同房间更高版本的新状态。

双方准备完成后，本阶段显示“双方已准备”，正式开局和地图加载放在下一阶段。关闭页面、断开连接和退出房间是不同操作：本阶段只有点击“退出房间”会释放后端席位。

## 协议变化

原连接 `/ws/tankgame`、原订阅 `/user/queue/replies` 和创建/加入路径保留。

| 功能 | 客户端目标及正文 | 成功确认 | 主动通知 |
| --- | --- | --- | --- |
| 准备 | `/app/player/ready`，`{"roomId":"当前房间号","ready":true}` | `room:ready`，完整房间快照 | 全体成员收到 `room:updated` |
| 取消准备 | 同上，`ready` 改为 `false` | 同上 | 同上 |
| 主动退出 | `/app/room/leave`，`{"roomId":"当前房间号"}` | `room:left`，`{"roomId":"已退出的房间号","version":5}` | 退出账号的所有连接收到 `room:left`；剩余成员收到 `room:updated` |

房间快照在 `roomId`、`ownerUserId`、`memberUserIds`、`version` 基础上增加 `readyUserIds`，表示已准备成员的用户 ID 列表。例如：

```json
{
  "type": "room:updated",
  "payload": {
    "roomId": "示例房间号",
    "ownerUserId": 14,
    "memberUserIds": [14, 15],
    "version": 3,
    "readyUserIds": [14]
  }
}
```

请求中的 `roomId` 必填且最多 36 个字符，用于检查当前成员关系；用户身份仍来自握手绑定的 `Principal`，不接受客户端决定操作对象。`ready` 使用 `Boolean` 配合 `@NotNull`，因此漏传会被拒绝，明确传 `false` 则合法。非法参数、房间不存在、非成员操作均返回现有 `type=error` 消息。已退出后再次请求退出会得到业务错误，不再修改任何房间；准备请求具有幂等性。

当前连接成功确认和账号广播可能都到达，重复收到同版本的 `room:left` 是预期行为。STOMP 入站白名单已加入两个新路径，原有代理消息伪造防护保留。

## 文件与调用关系

| 文件 | 类与方法、职责 |
| --- | --- |
| `backend/src/main/java/com/syf/blog/dto/game/room/PlayerReadyRequest.java` | `PlayerReadyRequest`：房间号与准备状态校验 |
| `backend/src/main/java/com/syf/blog/dto/game/room/LeaveRoomRequest.java` | `LeaveRoomRequest`：退出房间号校验 |
| `backend/src/main/java/com/syf/blog/dto/game/room/RoomLeftResponse.java` | `RoomLeftResponse`：已退出房间及退出版本 |
| `backend/src/main/java/com/syf/blog/dto/game/room/RoomResponse.java` | `RoomResponse`：新增准备名单副本 |
| `backend/src/main/java/com/syf/blog/game/runtime/GameRoom.java` | `GameRoom`：准备成员集合和可转移的房主字段 |
| `backend/src/main/java/com/syf/blog/game/runtime/RoomLeaveResult.java` | `RoomLeaveResult`：退出确认与剩余房间快照，空房时剩余快照为 null |
| `backend/src/main/java/com/syf/blog/service/game/GameRoomService.java` | `setReady()`、`leaveRoom()` 执行业务；`requireMemberRoom()` 校验成员；`createResponse()` 复制快照 |
| `backend/src/main/java/com/syf/blog/controller/GameMessageController.java` | `setReady()`、`leaveRoom()` 绑定请求和连接身份、组织回复与推送 |
| `backend/src/main/java/com/syf/blog/websocket/game/GameRoomNotifier.java` | `notifyRoomLeft()` 通知退出账号所有连接，原 `notifyRoomUpdated()` 通知剩余成员 |
| `backend/src/main/java/com/syf/blog/websocket/game/GameMessageChannelInterceptor.java` | 新业务路径白名单 |
| `frontend/src/views/WebSocketTestView.vue` | 准备状态展示、按钮、请求等待状态、退出消息和旧通知处理 |

调用链：页面按钮 → STOMP 入站目标校验 → Controller 的 `@Valid` 请求校验与身份读取 → Service 同步修改内存房间 → 返回独立快照 → 通知器推送 → 前端校验版本后更新界面。

房间、成员索引、准备名单仍只保存在后端内存中。全部读写在服务的同步方法内串行完成，通知在锁外发送，网络发送不会占用业务锁。房间为空时删除房间 Map 项，退出时删除用户索引；进程重启后全部清空。本阶段不增加数据库表或基础设施。

Lombok 说明：`GameRoom` 使用 `@RequiredArgsConstructor` 生成 `(roomId, ownerUserId)` 构造方法；房主需要转移，因此 `ownerUserId` 改为可变字段，使用字段级 `@Setter` 和 `@NonNull`，其他身份字段保持 final。`RoomResponse` 构造参数依次是房间号、房主 ID、成员名单、版本、准备名单；`RoomLeftResponse` 接收房间号和版本；`RoomLeaveResult` 接收退出响应和剩余快照。两个请求类用 `@NoArgsConstructor`、getter/setter 供消息绑定使用。

## 基础检查

| 检查 | 结果 |
| --- | --- |
| `GameRoomServiceTest` | 18 项通过：准备、退出、房主转移、回收、权限拒绝、版本与名单副本 |
| `GameMessageControllerTest` | 10 项通过：成功确认、成员通知范围、退出账号通知、失败不广播 |
| `GameMessageChannelInterceptorTest` | 14 项通过：新旧合法目标、越权发送/订阅及协议绕过拒绝 |
| `dto.game.room.RoomRequestValidationTest` | 3 项通过：缺失 ready 与显式 false 的区别、房间号约束 |
| 前端 `npm run build` | 通过，包含 Vue/TypeScript 类型检查和生产构建 |
| 测试页 ESLint 与格式检查 | 通过 |
| `git diff --check` | 通过 |
| 两个独立浏览器的实际联调 | 等待用户手测 |

后端本阶段相关测试共 45 项，0 失败、0 错误、0 跳过；使用内存服务和模拟发送组件，不连接或修改开发数据库。未运行依赖数据库的全量上下文测试。复现命令：

```powershell
cd D:\code\springboot\blog-ai\backend
.\mvnw.cmd test "-Dtest=GameRoomServiceTest,GameMessageControllerTest,GameMessageChannelInterceptorTest,com.syf.blog.dto.game.room.RoomRequestValidationTest" "-Dspring.jpa.hibernate.ddl-auto=validate" "-Dspring.sql.init.mode=never" "-Ddebug=false"
```

交付前已重启助手之前启动的后台副本，08:56:50 启动日志确认目录为 `blog-ai\backend\target\classes`、端口为 8081。无 Cookie 查询当前用户正常返回 401。后端使用 `ddl-auto=validate`、`spring.sql.init.mode=never`，未更新表结构或执行初始化脚本。运行输出仍在 `backend/target/backend-manual-test.log`；可以直接重新登录后手测，无需再次从 IDEA 启动。

## 手动验收

后端代码必须加载自 `D:\code\springboot\blog-ai\backend\target\classes`。前次已发生过前端运行副本、后端却运行原项目的问题，请检查后端启动日志中的目录。后端重启会清空房间和登录会话，需要重新登录；使用终端中 Vite 实际输出的前端地址。

手动启动方式（若助手已重启后台后端，则不需要重复启动）：

```powershell
cd D:\code\springboot\blog-ai\backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.jpa.hibernate.ddl-auto=validate --spring.sql.init.mode=never --debug=false"
```

```powershell
cd D:\code\springboot\blog-ai\frontend
npm run dev
```

请用两个独立浏览器环境登录账号 A、B，打开 `/game/ws-test` 并连接。A 创建房间，B 直接输入 A 的房间号加入。

| 编号 | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | 检查初始房间 | 两边均为 2/2 人，A 为房主，两人未准备 |
| 2 | A 点击准备 | 两边同时显示 A 已准备、B 未准备；A 按钮变为取消准备 |
| 3 | B 点击准备 | 两边同时显示双方已准备，不自动开始游戏 |
| 4 | A 点击取消准备 | 两边均只保留 B 已准备；A 再准备可恢复 |
| 5 | B 点击退出房间 | B 清空房间显示，可创建或加入；A 显示 1/2 人且未准备 |
| 6 | B 重新加入，然后两人准备 | 两边状态一致，准备状态可以重新设置 |
| 7 | 房主 A 点击退出房间，A 暂时不创建/加入别的房间 | A 清空显示，B 自动成为房主，B 的准备清空 |
| 8 | B 退出原房间，A 尝试加入原房间号 | 原空房已回收，A 收到房间不存在；两人仍可重新创建房间 |
| 9 | A 单独创建房间并准备，B 加入 | B 加入后 A 的准备也被清空，双方都需重新确认 |
| 10 | 在同一账号的第二个标签页连接并取回当前房间，再从其中一个标签页退出 | 该账号的两个标签页都清空旧房间，其他成员同步看到退出 |

如需核对版本，从消息日志查看：每次真实准备变化或成员变化均增加 1；重复设置同一状态不增加。可在控制台观察 Controller、Service 和通知器带类名的方法输出。

测试失败时提供编号、两个页面的状态与消息日志、后端对应输出即可，不要提供 Cookie、密码或会话 ID。

## 边界与下一阶段

本阶段不处理断线自动退房、登录失效撤销、加载超时或自动重连。跨房间的迟到退出/准备请求会被成员关系校验拒绝；同一账号通过多条连接退出并重新加入同一房间后，尚未实现针对旧请求的成员代次校验或请求去重。前端的版本判断保护迟到响应，不能代替后端请求去重。

本阶段手测后，再进入房主开始、地图加载与双方确认流程。当前不会启动权威战局或计算移动、射击。
