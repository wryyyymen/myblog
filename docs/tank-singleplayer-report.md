# 单人 PVE 坦克大战验收报告

日期：2026-11-18。状态：基础战斗已实现，前端服务已运行，等待用户手动验收。

## 直接试玩

打开 **http://localhost:5173/game/single**，点击“开始游戏”。`/game` 现为菜单，最新入口与联机说明见 [菜单与合作验收报告](tank-menu-online-report.md)。无需登录或创建房间，单人规则在浏览器本地运行，不依赖后端游戏连接。

- WASD / 方向键：移动与转向。
- 空格：按住连续开火，松开停止。
- P / Esc：暂停或继续；离开窗口自动暂停。
- R：重新开始。也可点击界面按钮。

## 基础玩法

一张 26×26 格的经典风格地图，玩家绿色坦克从底部出发，保护鹰形基地，消灭8辆敌军获胜。敌军分批从顶部出现，最多同时3辆；会寻找通行路线、绕开障碍、追击玩家并射击基地或玩家。

玩家初始3点装甲，敌军每辆1点装甲。击毁敌军得100分；玩家装甲归零或敌方子弹击中基地则失败。开局有3秒护盾，受击后有0.9秒保护。重开会恢复地图、装甲、基地和敌军数量。

砖墙可以击毁，钢墙不能击毁，河流阻挡坦克但不挡子弹；树林提供视觉遮挡，冰面当前按普通地面通行。坦克不能穿越墙体、基地或其他坦克。子弹沿路径检测最近命中，不穿墙，不造成同阵营伤害；玩家子弹不会损坏自己的基地。

## 本次代码

| 文件 | 职责 |
| --- | --- |
| `frontend/src/game/singleplayer/engine.ts` | 独立规则：地图、固定步长移动、碰撞、射击、AI路径与瞄准、伤害、胜负、暂停 |
| `frontend/src/game/singleplayer/renderer.ts` | 使用已有PNG绘制地形、方向/履带帧、子弹、爆炸、护盾和基地；血条保留Canvas绘制 |
| `frontend/src/game/singleplayer/assets.ts` | 统一加载并缓存已有PNG，全部解码成功后允许开局，失败可重试；联机复用时增加黄色队友图 |
| `frontend/src/views/SinglePlayerGameView.vue` | 游戏页面、键盘、60Hz固定步长驱动、操作与战况、暂停/重开、资源清理 |
| `frontend/src/router/index.ts` | `/game` 为菜单，单人 `/game/single`，合作联机 `/game/online` |
| `frontend/src/App.vue` | 单人入口不等待登录状态恢复，保持即开即玩 |
| `frontend/scripts/test-singleplayer.mjs` | 使用已有TypeScript与Node测试工具，无新增依赖 |
| `frontend/package.json` | 增加 `npm run test:game` 命令 |

没有为单人模式新增数据库表、登录要求或网络基础设施。页面离开时取消动画、移除键盘与窗口事件；失焦和暂停时清空移动、开火输入。AI重新规划前对齐当前格子，已修复钢墙转角处反复卡住的复现。

## 验证结果

- 单人规则测试12项通过：分批刷怪、移动停止、地形碰撞、射击冷却/上限、破砖/钢墙/水、伤害与护盾、胜负、AI瞄准开火、绕墙回归、暂停重开、正常地图无人防守时AI能持续进攻。
- `npm run build` 通过，包含 Vue/TypeScript 类型检查和生产构建。
- 新增单人文件及相关页面 ESLint、格式检查、`git diff --check` 通过。
- 隔离的 Chrome 浏览器检查通过：匿名访问、开始、画布焦点、方向键/空格输入、P暂停、按钮重开、3血复位，无运行异常；已查看实际画面。
- 浏览器预览截图：`backend/target/singleplayer-preview.png`，仅为本地检查产物，未提交到Git。

复现基础检查：

```powershell
cd D:\code\springboot\blog-ai\frontend
npm run test:game
npm run build
```

启动前端：

```powershell
cd D:\code\springboot\blog-ai\frontend
npm run dev
```

## 用户手动验收

1. 打开 `/game`，未登录时也能看到开始界面；点击开始，能立即控制绿色坦克。
2. 移动并向砖墙开火，确认砖墙破坏后可通过，钢墙与河流阻挡移动。
3. 对抗顶部出现的红色敌军，确认敌军会移动、开火、扣血并被击毁，击杀数与得分同步增加。
4. 验证玩家装甲归零或基地受击后的失败；消灭全部8辆敌军后获胜。
5. 按P暂停、继续，按R重开，确认敌军、血量、地图与分数复位。

当前交付一关基础PVE，不含多关卡、道具、升级或排行榜。此前联机开发记录属于独立模式，当前验收以本报告和 `/game` 单人入口为准。

## 已有素材接入

初版单人画面曾使用Canvas图形绘制。本次已改为复用 `frontend/public/resources/png/` 素材，物理尺寸与战斗规则不变，没有生成或修改图片。

| 画面内容 | 复用文件 |
| --- | --- |
| 绿色玩家 | `players/tank_T2_0.png` |
| 红色敌军 | `enemies/enemy_1_3.png` |
| 砖、钢、水、树、冰 | `map/tiles/brick.png`、`iron.png`、`river1.png`、`river2.png`、`tree.png`、`ice.png` |
| 基地与毁坏状态 | `map/base/home.png`、`home_destroyed.png` |
| 四方向子弹 | `projectiles/bullet_up.png`、`bullet_down.png`、`bullet_left.png`、`bullet_right.png` |
| 爆炸 | `effects/boom_dynamic.png` |
| 护盾 | `effects/protect.png` |

坦克图表按48×48裁切：两列履带帧，四行上/下/左/右。爆炸按96×96裁切6帧，护盾按48×48裁切2帧，河流交替两张图。保留像素清晰度与树林遮挡，爆炸颜色沿用原图的紫白色。声音与道具素材尚未接入。

素材替换后生产构建、类型检查、相关文件ESLint和空白检查通过。隔离浏览器中实际拦截并核对 `drawImage` 的图片路径，确认玩家、敌军、地形、基地、子弹、爆炸、护盾确实使用PNG绘制；开始、键盘移动/开火、暂停、重开检查通过且无运行异常，预览截图已更新。
