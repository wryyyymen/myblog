import type { Direction } from './protocol'

export interface Position {
  x: number
  y: number
}

/** 只在已确认的起点、终点间补帧，不越过服务端确认的位置。 */
export function interpolatePosition(
  from: Position,
  target: Position,
  direction: Direction,
  progress: number,
): Position {
  const fraction = Math.max(0, Math.min(1, progress))
  const differenceX = target.x - from.x
  const differenceY = target.y - from.y
  const distanceX = Math.abs(differenceX)
  const distanceY = Math.abs(differenceY)
  const totalDistance = distanceX + distanceY
  if (totalDistance < 0.00001) return { x: target.x, y: target.y }

  // 两次快照之间转弯时沿两段直线移动，避免斜穿墙角或突然跳到终点。
  const travelled = totalDistance * fraction
  if (direction === 0 || direction === 1) {
    const horizontal = Math.min(distanceX, travelled)
    const vertical = Math.max(0, travelled - distanceX)
    return {
      x: from.x + Math.sign(differenceX) * horizontal,
      y: from.y + Math.sign(differenceY) * vertical,
    }
  }
  const vertical = Math.min(distanceY, travelled)
  const horizontal = Math.max(0, travelled - distanceY)
  return {
    x: from.x + Math.sign(differenceX) * horizontal,
    y: from.y + Math.sign(differenceY) * vertical,
  }
}

/** 丢包或同步后不把一大段位移拖成慢动作，最长只补 200ms。 */
export function interpolationDuration(
  previousTick: number,
  tick: number,
  tickMillis: number,
): number {
  return Math.max(tickMillis, Math.min(200, (tick - previousTick) * tickMillis))
}
