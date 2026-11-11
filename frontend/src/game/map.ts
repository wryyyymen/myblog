export const TILE_SIZE = 24; // 每个地图格子的边长为 24 像素

// 材质编号与后端 GameMapResponse 的 seed 约定一致：
// 0 空地、1 砖墙、2 钢墙、3 河流、4 树林、5 冰面
const BRICK = 1
const IRON = 2
const WATER = 3
const TREE = 4
const ICE = 5

const cols = 40;
const rows = 26;

// 先铺满空地，再往上摆障碍。
// 这样改布局时只需要增删 paint 调用，不用手写 26 行数组。
export const gameMap: number[][] = Array.from(
  { length: rows },
  () => Array<number>(cols).fill(0),
)

/**
 * 在地图上刷一块矩形区域，左上角是第 row 行、第 column 列。
 *
 * 只修改数组内容、不重新赋值，所以 gameMap 依然可以声明为 const。
 *
 * @param row 起始行号，从 0 开始
 * @param column 起始列号，从 0 开始
 * @param height 占几行
 * @param width 占几列
 * @param code 材质编号
 */
function paint(
  row: number,
  column: number,
  height: number,
  width: number,
  code: number,
): void {
  for (let r = row; r < row + height; r++) {
    const line = gameMap[r]

    // 越界的绘制直接跳过，避免改布局时把行号写大了导致报错
    if (!line) continue

    for (let c = column; c < column + width; c++) {
      line[c] = code
    }
  }
}

// 四周一圈砖墙。坦克出生在 (1,1)，走不出这张图。
paint(0, 0, 1, cols, BRICK)
paint(rows - 1, 0, 1, cols, BRICK)
paint(0, 0, rows, 1, BRICK)
paint(0, cols - 1, rows, 1, BRICK)

// 以下六块用来验证每种材质的通行规则。
// 每一块都放在出生点附近，打开页面就能走过去试。
paint(1, 6, 4, 1, BRICK) // 竖砖墙：出生点向右走约 1.3 秒撞上
paint(5, 12, 1, 7, BRICK) // 横砖墙：从右侧绕过去再往左撞
paint(4, 22, 3, 1, IRON) // 钢墙：和砖墙一样挡住，但将来不可被子弹打掉
paint(9, 30, 6, 1, WATER) // 河流：挡住坦克，子弹可以飞过
paint(18, 8, 3, 4, TREE) // 树林：可以通行，坦克能从里面穿过去
paint(21, 22, 2, 4, ICE) // 冰面：首版按普通地面处理
