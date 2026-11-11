// 坦克和子弹与地图的碰撞判定。
//
// 和 playerMovement.ts 一样写成纯函数：只读传入的地图，不改任何状态，
// 也不访问 DOM 和 window。将来把碰撞规则搬到服务端时，
// 这段判断可以逐行翻译成 Java，不需要重新设计。

import type { GameMapData } from './mapLoader'

// 会挡住坦克的材质：砖墙 1、钢墙 2、河流 3。
// 树林 4 可以穿过，冰面 5 首版按普通地面处理，两者都不算阻挡。
const TANK_BLOCKING = new Set([1, 2, 3])

// 会挡住子弹的材质：砖墙 1、钢墙 2。
// 河流挡坦克但不挡子弹，树林和冰面同样可以飞过。
// 两套规则分开，是因为"坦克过不去"和"子弹飞不过"不是同一件事。
const BULLET_BLOCKING = new Set([1, 2])

/**
 * 判断一个方形实体放在 (x, y) 时，是否与指定的阻挡材质重叠。
 *
 * 只负责几何换算和查表，哪些材质算阻挡由调用方通过 blocking 传入。
 * 坐标记的是实体左上角，实际占用范围是 [x, x + size) × [y, y + size)。
 *
 * @param map 当前地图，用 tileSize 做像素到格子的换算
 * @param x 实体左上角的横坐标，单位像素
 * @param y 实体左上角的纵坐标，单位像素
 * @param size 实体边长，单位像素
 * @param blocking 本次判定视为阻挡的材质编号集合
 * @returns 与阻挡材质或地图边界重叠时返回 true
 */
function overlapsBlockingTile(
  map: GameMapData,
  x: number,
  y: number,
  size: number,
  blocking: ReadonlySet<number>,
): boolean {
  const tile = map.tileSize
  const rowCount = map.seed.length
  const columnCount = map.seed[0]?.length ?? 0

  // 地图尺寸按行列数和格子边长推导，不依赖画布大小。
  // 越界一律当阻挡，效果和四周那圈砖墙一致。
  if (x < 0 || y < 0) return true
  if (x + size > columnCount * tile) return true
  if (y + size > rowCount * tile) return true

  // 把像素范围换算成格子范围。
  // 右边缘和下边缘减 1，是为了让实体刚好贴着格子边界时不多算一格；
  // 不减的话，坦克停在 x=120 会被判成同时占第 5 和第 6 列。
  const left = Math.floor(x / tile)
  const right = Math.floor((x + size - 1) / tile)
  const top = Math.floor(y / tile)
  const bottom = Math.floor((y + size - 1) / tile)

  for (let row = top; row <= bottom; row++) {
    const line = map.seed[row]

    // 行不存在说明地图数据不完整，按阻挡处理，避免穿到图外
    if (!line) return true

    for (let column = left; column <= right; column++) {
      // 列越界同样按阻挡处理
      const code = line[column]

      if (code === undefined) return true
      if (blocking.has(code)) return true
    }
  }

  return false
}

/**
 * 判断坦克放在 (x, y) 时会不会和阻挡物重叠。
 * 砖墙、钢墙、河流都挡坦克。
 */
export function isBlocked(
  map: GameMapData,
  x: number,
  y: number,
  size: number,
): boolean {
  return overlapsBlockingTile(map, x, y, size, TANK_BLOCKING)
}

/**
 * 判断子弹放在 (x, y) 时是不是打在了墙上。
 * 河流不挡子弹，所以子弹可以飞过河面。
 */
export function blocksBullet(
  map: GameMapData,
  x: number,
  y: number,
  size: number,
): boolean {
  return overlapsBlockingTile(map, x, y, size, BULLET_BLOCKING)
}
