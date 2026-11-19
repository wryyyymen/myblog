# 坦克结算页、房间列表与声音验收报告

日期：2026-11-19。三个功能已实现，等待用户手动验收。

## 游戏结束与结算

单机胜利或失败后自动进入 `/game/single/result`。联机结束后，双方自动进入 `/game/online/result/{matchId}`。

结算画面显示胜利、失败、平局或合作中止，以及本局/团队得分、击毁数量、战斗用时、剩余血量和基地状态。单机可以直接再来一局；联机由房主重开，双方重新准备。返回大厅或菜单时，联机先完成退房，再离开页面。

联机使用嵌套路由，结算期间父页面继续维护原 WebSocket，避免切换结算页导致掉线。刷新可从当前标签页的 `sessionStorage` 恢复显示数据，并向后端重新查询原房间；本地缓存不用于决定服务端胜负，也不保存登录凭据。

## 房间列表

大厅连接成功后自动加载房间列表，每 3 秒更新一次，也支持手动刷新和点击“加入”。

后端仅列出同时满足以下条件的房间：处于 `WAITING`、未满两人、至少有一名成员、房主在线，且不是查询者自己的房间。合作与存量对战模式有明确区分；满员、开局、房主离线或空房回收后不再展示。最终加入仍由后端检查，不能依靠列表中的旧状态抢占已满房间。

新增 STOMP 指令 `/app/room/list`，回复 `room:list`，仅发送给查询连接。摘要字段为 `roomId、ownerUserId、memberCount、maxMembers、mode、version`；按房间号排序，最多返回 100 个。查询只复制内存快照，不创建房间，也不修改成员。

## 游戏声音

已把用户提供目录中的七个 WAV 复制到 `frontend/public/resources/audio/tank`，约 1.10 MiB。运行时不依赖桌面或 OneDrive 路径。

| 事件 | 使用资源 |
| --- | --- |
| 开局 | `start.wav` |
| 射击 | `Gunfire.wav`，沿用原项目用途 |
| 受击 | `hit.wav` |
| 破砖 | `fire.wav` |
| 击毁 | `bang.wav`，沿用原项目用途 |
| 胜利 | `add.wav` |
| 失败 | `blast.wav` |

菜单、战场和结算页都有声音开关与音量滑块，默认音量 50%，设置在当前浏览器保存。声音由开始、准备、按键等用户交互解锁；音频加载失败不会阻塞游戏。

同一音频只解码并缓存一次，同时最多播放 8 个音源。声效根据真正的新子弹、伤害、破砖和胜负变化触发，不随绘制帧反复播放。窗口失焦、隐藏、暂停或离开时停止当前声音；重连补同步不会重播历史枪声和旧结算声。已处理 `AudioContext.resume()` 长时间挂起时，后续真实点击或按键可重新尝试解锁的边界。

## 关键代码

| 文件 | 职责 |
| --- | --- |
| `frontend/src/views/GameResultView.vue` | 共用的独立结算画面 |
| `frontend/src/game/results.ts` | 结算数据、校验、安全缓存、单机映射 |
| `frontend/src/router/index.ts` | 保留父页面的单机/联机结算子路由 |
| `frontend/src/views/SinglePlayerGameView.vue` | 单机结束跳转、重开和声音事件 |
| `frontend/src/views/GameView.vue` | 联机结算、重开/退出、列表轮询与加入、声音事件 |
| `frontend/src/game/network/protocol.ts` | 房间摘要 JSON 校验 |
| `frontend/src/game/audio/audioEvents.ts` | 纯音效事件判断与去重 |
| `frontend/src/game/audio/gameAudio.ts` | Web Audio 播放、缓存、解锁、音量及清理 |
| `frontend/src/components/GameAudioControls.vue` | 共用声音开关与音量 |
| `backend/src/main/java/com/syf/blog/dto/game/room/RoomListResponse.java` | 列表摘要 DTO |
| `backend/src/main/java/com/syf/blog/service/game/GameRoomService.java` | 同步筛选可加入房间 |
| `backend/src/main/java/com/syf/blog/controller/GameMessageController.java` | 列表消息入口与定向回复 |

## 自动验证

- 后端游戏测试 152 项通过，包括房间筛选、只读快照、并发访问、成员变化及真实 STOMP 列表。
- 前端测试 42 项通过，包括单机规则、网络字段、补帧、结果映射与缓存、声音事件去重。
- `npm run build` 包含 Vue/TypeScript 检查和生产构建，已通过。
- 结算与列表浏览器流程已通过：真实单机失败跳转、刷新和重开；列表发现、一键加入、满员移除；双方联机结算不掉线、刷新恢复、重开、重连、合作中止与退房清理。
- 生产构建的声音浏览器检查通过：七个 WAV 均返回 200 且成功解码，记录到开局和射击的真实 `AudioBufferSourceNode.start()` 调用；静音后没有新的播放调用，25% 音量设置在刷新后保留，刷新旧结果不重播结束声。没有浏览器运行异常。
- 已查看单机结算、房间列表和联机结算截图。声音开关的实际扬声器听感不以浏览器调用记录替代。

浏览器验证使用 `src/test` 下的隔离服务及测试身份，不读写开发数据库。截图和检查日志保存在忽略的 `backend/target`。实际扬声器音色、响度和混音效果仍需手动听音确认。

复现规则测试：

```powershell
cd D:\code\springboot\blog-ai\backend
.\mvnw.cmd test "-Dtest=Game*Test,com.syf.blog.dto.game.room.RoomRequestValidationTest" "-Dspring.jpa.hibernate.ddl-auto=validate" "-Dspring.sql.init.mode=never" "-Ddebug=false"
cd ..\frontend
npm run test:game
npm run build
```

## 手动验收顺序

本次增加了后端消息入口，先重启现有后端，再刷新前端。分别从 `backend` 运行 `.\mvnw.cmd spring-boot:run`、从 `frontend` 运行 `npm run dev`，进入 <http://localhost:5173/game>。

1. **单机结算**：开始游戏，验证胜败后自动进入结算，分数、用时和血量正确；刷新后记录仍在，“再来一局”恢复初始状态。
2. **列表加入**：用两个独立浏览器登录不同账号。A 创建房间，B 在列表点击加入；第三个账号不再看到已满房间。退房后可用房间重新出现。
3. **联机结算**：双方结束后看到相同胜负；房主再来一局，双方回到等待并重新准备。结算页刷新后仍能重开；返回大厅或菜单时正确退房。
4. **声音**：点击开始或“开启声音”，听开局、射击、受击、破砖、爆炸和结束声音；切换静音并调节音量，再刷新确认设置保存。切走窗口应停止声音。

验收时请反馈失败阶段、操作过程及两端日志；听感问题请说明具体音效和音量设置。
