export const TILE_SIZE = 24; // 每个地图格子的边长为 24 像素

const cols = 40;
const rows = 26;

export const gameMap: number[][] = Array.from(
  { length: rows },
  (_, row) => {
    // 为当前这一行生成 40 个格子
    return Array.from({ length: cols }, (_, column) => {
      // 判断当前格子是否位于地图四周
      const isBorder =
        row === 0 ||
        row === rows - 1 ||
        column === 0 ||
        column === cols - 1

      return isBorder ? 1 : 0
    })
  },
)
