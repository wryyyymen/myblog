import type { PlayerState } from './playRenderer'

// 坦克每秒移动的像素数。
// 一格是 24 像素，等于每秒走 3 格。接入联机后这个值要放到服务端，
// 由服务端决定速度，客户端不能自己改。
export const PLAYER_SPEED = 72

// 一帧最多推进的时间，单位是秒。
// 标签页切到后台再切回来时，两帧之间可能隔了好几秒；
// 不加限制的话，坦克会在这第一帧里瞬移到很远的位置。
const MAX_STEP_SECONDS = 0.1

// 方向编号与像素位移的对应关系。
// 0 上、1 下、2 左、3 右，与键盘模块、绘制器共用同一套编号，
// 不要在别处另定一套，否则转向和移动会对不上。
const DIRECTION_DELTA: Record<0 | 1 | 2 | 3, { dx: number; dy: number }> = {
  0: { dx: 0, dy: -1 },
  1: { dx: 0, dy: 1 },
  2: { dx: -1, dy: 0 },
  3: { dx: 1, dy: 0 },
}

// 可移动区域的宽高，通常就是画布尺寸。
export interface MoveBounds {
  width: number
  height: number
}

/**
 * 按方向和经过的时间推进坦克位置，返回新坐标。
 *
 * 这里写成纯函数：只读参数，不修改传入的 player，也不访问 DOM 和 window。
 * 将来把移动逻辑搬到服务端时，这段实现可以逐行翻译成 Java，
 * 不需要重新想一遍算法。
 *
 * @param player 当前玩家状态，只读取 x、y、size
 * @param direction 移动方向，0 上、1 下、2 左、3 右
 * @param dt 距离上一帧经过的秒数
 * @param bounds 可移动区域的宽高
 * @returns 计算后的新坐标，保证不超出可移动区域
 */
export function movePlayer(
  player: PlayerState,
  direction: 0 | 1 | 2 | 3,
  dt: number,
  bounds: MoveBounds,
): { x: number; y: number } {
  const delta = DIRECTION_DELTA[direction]

  // 这一帧走的距离 = 每秒速度 × 经过时间。
  // 必须乘时间，否则在 120Hz 屏幕上坦克会比 60Hz 快一倍。
  const distance = PLAYER_SPEED * Math.min(dt, MAX_STEP_SECONDS)

  // 坐标记的是坦克左上角，右下角不能超出画布，
  // 所以上限要减去坦克自身尺寸。
  const maxX = bounds.width - player.size
  const maxY = bounds.height - player.size

  return {
    x: clamp(player.x + delta.dx * distance, 0, maxX),
    y: clamp(player.y + delta.dy * distance, 0, maxY),
  }
}

/** 把数值限制在 min 与 max 之间。 */
function clamp(value: number, min: number, max: number): number {
  if (value < min) return min
  if (value > max) return max
  return value
}
