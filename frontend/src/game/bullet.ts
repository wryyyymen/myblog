// 子弹的运行时状态与推进规则。
// 与 playerMovement.ts 一样写成纯函数：只读参数、返回新数据，不碰 DOM，
// 将来接入联机时可以直接搬到服务端。

// 子弹每秒移动的像素数。报告 F2 模块里定的值。
export const BULLET_SPEED = 240

// 子弹图片的边长，四张图都是 12×12。
export const BULLET_SIZE = 12

// 开火冷却时间，单位毫秒。冷却期内按空格不产生新子弹。
export const FIRE_COOLDOWN_MS = 300

// 同一玩家场上同时存在的子弹上限。首版只允许一颗。
export const MAX_BULLETS_PER_PLAYER = 1

// 方向编号到单位位移的映射，和前端的移动、绘制共用同一套 0~3 编号。
const DELTA: Record<0 | 1 | 2 | 3, { dx: number; dy: number }> = {
  0: { dx: 0, dy: -1 },
  1: { dx: 0, dy: 1 },
  2: { dx: -1, dy: 0 },
  3: { dx: 1, dy: 0 },
}

export interface BulletData {
  // 子弹编号，用于区分同一帧里的多颗子弹
  id: number
  // 当前坐标，记的是子弹图片左上角
  x: number
  y: number
  // 飞行方向，生成时确定，之后不再改变
  direction: 0 | 1 | 2 | 3
  // 发射者的用户标识，联机时用来区分敌我，单机版固定为 'local'
  ownerId: string
}

// 简单的自增编号，够单机版用。联机时改由服务端分配。
let nextBulletId = 1

/**
 * 在坦克的炮口位置生成一颗子弹。
 *
 * 坦克坐标记的是左上角，尺寸 24；子弹尺寸 12。
 * 要让子弹看起来从炮口出来，就要先取坦克中心，再按方向推到边缘：
 *   朝上：x 居中，y 挪到坦克上边外侧
 *   朝下：x 居中，y 挪到坦克下边外侧
 *   朝左：y 居中，x 挪到坦克左边外侧
 *   朝右：y 居中，x 挪到坦克右边外侧
 *
 * @param tank 发射者，只用到 x、y、size
 * @param direction 飞行方向
 * @param ownerId 发射者标识
 */
export function createBullet(
  tank: { x: number; y: number; size: number },
  direction: 0 | 1 | 2 | 3,
  ownerId = 'local',
): BulletData {
  // 坦克中心减去子弹半径，让子弹在垂直方向上居中
  const centerX = tank.x + tank.size / 2 - BULLET_SIZE / 2
  const centerY = tank.y + tank.size / 2 - BULLET_SIZE / 2

  // 再往朝向那一侧推出去，推到坦克边缘之外
  const offset = tank.size / 2

  let x = centerX
  let y = centerY

  if (direction === 0) y -= offset
  if (direction === 1) y += offset
  if (direction === 2) x -= offset
  if (direction === 3) x += offset

  return { id: nextBulletId++, x, y, direction, ownerId }
}

/**
 * 按时间推进所有子弹，返回新数组。
 *
 * 和坦克移动一样必须乘 dt：不乘的话在 144Hz 屏幕上子弹会飞得更快。
 *
 * @param bullets 当前子弹列表
 * @param dt 距离上一帧经过的秒数
 */
export function advanceBullets(bullets: BulletData[], dt: number): BulletData[] {
  const distance = BULLET_SPEED * dt
  return bullets.map((bullet) => {
    const delta = DELTA[bullet.direction]
    return {
      ...bullet,
      x: bullet.x + delta.dx * distance,
      y: bullet.y + delta.dy * distance,
    }
  })
}

/**
 * 移除飞出画布的子弹。
 *
 * 判定用"完全离开"而不是"碰到边缘"：子弹中心一旦越过边界就删掉，
 * 避免子弹停在画面边缘一直不消失。
 */
export function removeOutOfBounds(
  bullets: BulletData[],
  width: number,
  height: number,
): BulletData[] {
  const half = BULLET_SIZE / 2
  return bullets.filter(
    (bullet) =>
      bullet.x + half >= 0 &&
      bullet.x - half <= width &&
      bullet.y + half >= 0 &&
      bullet.y - half <= height,
  )
}
