// 素材根路径：对应 public/resources/，并兼容部署时配置的基础路径
const resourceBase = `${import.meta.env.BASE_URL}resources/`;

// 统一维护图片地址
export const gameAssets = {
  brick: `${resourceBase}png/map/tiles/brick.png`,
  iron: `${resourceBase}png/map/tiles/iron.png`,
  water: `${resourceBase}png/map/tiles/river1.png`,
  tree: `${resourceBase}png/map/tiles/tree.png`,
  ice: `${resourceBase}png/map/tiles/ice.png`,
  playerTank: `${resourceBase}png/players/tank_T1_0.png`,
  // 子弹按方向分成四张图，每张 12×12
  bulletUp: `${resourceBase}png/projectiles/bullet_up.png`,
  bulletDown: `${resourceBase}png/projectiles/bullet_down.png`,
  bulletLeft: `${resourceBase}png/projectiles/bullet_left.png`,
  bulletRight: `${resourceBase}png/projectiles/bullet_right.png`,
}

// 子弹方向编号对应图片：0 上、1 下、2 左、3 右
export const bulletAssets: Record<number, string> = {
  0: gameAssets.bulletUp,
  1: gameAssets.bulletDown,
  2: gameAssets.bulletLeft,
  3: gameAssets.bulletRight,
}

// 地图中的整数对应图片地址
// Record<number, string> 表示：键是数字，值是字符串
export const tileAssets: Record<number, string> = {
  1: gameAssets.brick, // 砖墙
  2: gameAssets.iron,  // 钢墙
  3: gameAssets.water, // 河流
  4: gameAssets.tree,  // 树林
  5: gameAssets.ice,   // 冰面
}
