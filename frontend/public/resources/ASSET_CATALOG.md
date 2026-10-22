# 坦克大战素材目录

本目录已按**原始素材套装**和**用途**分类。`png/` 来自原 `image/`，`gif/` 来自原 `img2/`；声音放在 `audio/`。每个文件原名保持不变。完整的原路径、现路径、大小和 SHA-256 见 [ASSET_MANIFEST.csv](./ASSET_MANIFEST.csv)。

```text
resources/
├─ png/                         PNG 素材套装（53 个）
│  ├─ players/                   玩家坦克（6）
│  ├─ enemies/                   敌方坦克（16）
│  ├─ projectiles/               子弹方向图（4）
│  ├─ powerups/                  道具（7）
│  ├─ effects/                   出生、爆炸、护盾、遮罩（5）
│  ├─ map/
│  │  ├─ tiles/                  砖、铁、冰、水、树（6）
│  │  └─ base/                   基地及损坏状态（3）
│  ├─ ui/                        背景、标志、结束与提示（4）
│  └─ reference/                 地图示例与截图（2）
├─ gif/                         GIF 素材套装，含一张 PNG（51 个）
│  ├─ players/                   两名玩家的方向图（8）
│  ├─ enemies/                   三类敌人的方向图（12）
│  ├─ projectiles/               双方子弹（2）
│  ├─ powerups/                  炸弹、星星、计时器（3）
│  ├─ effects/                   爆炸、出生、损坏（13）
│  ├─ map/tiles/                 地形（6）
│  └─ ui/                        标题、菜单、选择与状态图（7）
└─ audio/                       WAV 音频（12 个）
   ├─ main/                      原 music/（7）
   │  ├─ music/                  开始音乐（1）
   │  └─ effects/                操作、开火、击中、爆炸等（6）
   └─ from-gif-set/              原 img2/（5）
      ├─ music/                  开始音乐（1）
      └─ effects/                操作、开火、击中、爆炸（4）
```

## 使用建议

- 两套图片的尺寸和风格可能不同，先选一套作为游戏主视觉；另一套可供参考或作为备选。不要只凭同名用途混用。
- `png/reference/` 是地图示例与截图，适合参考布局，不应直接当坦克或地图图块使用。
- `gif/map/tiles/grass.png` 属于 GIF 套装，但文件格式确实是 PNG，因此按套装放在 `gif/` 下。
- `audio/from-gif-set/` 中的 `add.wav`、`blast.wav`、`fire.wav`、`start.wav` 与 `audio/main/` 中的对应文件内容完全相同；目前保留两份，便于追溯原套装。`hit.wav` 与主音频中的同名文件不同。
- 这些文件目前位于 `D:\code\springboot\resources`，浏览器不能仅凭这个磁盘路径访问。接入博客时，应将选中的素材复制或构建到博客前端的静态资源目录，并按最终公开路径引用。
- 素材来源和可公开使用的授权信息尚未记录。发布到服务器前，补充来源与授权记录。
