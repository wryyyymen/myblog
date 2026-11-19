/** 单机与联机共用的素材帧、配色和坦克附加效果。 */
export const BATTLE_COLORS = {
  player: '#67e8a7',
  teammate: '#ffe08a',
  enemy: '#ff958a',
  lowHealth: '#e86b43',
  emptyHealth: '#54615a',
  healthBackground: '#090d08',
  battlefield: '#10140f',
} as const

export type TankAppearance = 'player' | 'teammate' | 'enemy'

interface TankDrawingState {
  x: number
  y: number
  size: number
  direction: 0 | 1 | 2 | 3
}

const TANK_FRAME_SIZE = 48
const TREAD_FRAME_SECONDS = 0.12
const SHIELD_FRAME_SECONDS = 0.1

/** 两列履带帧、四行方向；两种模式采用相同的节奏和像素对齐。 */
export function drawTankSprite(
  context: CanvasRenderingContext2D,
  tank: TankDrawingState,
  image: HTMLImageElement,
  timeSeconds: number,
) {
  const frame = Math.floor(timeSeconds / TREAD_FRAME_SECONDS) % 2
  context.drawImage(
    image,
    frame * TANK_FRAME_SIZE,
    tank.direction * TANK_FRAME_SIZE,
    TANK_FRAME_SIZE,
    TANK_FRAME_SIZE,
    Math.round(tank.x),
    Math.round(tank.y),
    tank.size,
    tank.size,
  )
}

/** 血条统一置于坦克上方：每段代表 1 点血量，不增加名称或坦克外框。 */
export function drawTankHealth(
  context: CanvasRenderingContext2D,
  tank: TankDrawingState,
  hp: number,
  maxHp: number,
  appearance: TankAppearance,
) {
  if (hp <= 0) return
  const capacity = Math.max(1, Math.floor(maxHp))
  const remaining = Math.max(0, Math.min(capacity, hp))
  const left = Math.round(tank.x)
  const top = Math.max(1, Math.round(tank.y) - 5)
  const gap = 2
  const segmentWidth = Math.max(1, (tank.size - (capacity - 1) * gap) / capacity)
  const color = remaining / capacity <= 0.34 ? BATTLE_COLORS.lowHealth : BATTLE_COLORS[appearance]

  context.fillStyle = BATTLE_COLORS.healthBackground
  context.fillRect(left - 1, top - 1, tank.size + 2, 5)
  for (let segment = 0; segment < capacity; segment++) {
    context.fillStyle = segment < remaining ? color : BATTLE_COLORS.emptyHealth
    context.fillRect(left + segment * (segmentWidth + gap), top, segmentWidth, 3)
  }
}

/** 开局和受击保护共用同一张两帧素材；是否保护由各模式的规则状态决定。 */
export function drawTankShield(
  context: CanvasRenderingContext2D,
  tank: TankDrawingState,
  image: HTMLImageElement,
  timeSeconds: number,
) {
  const size = tank.size * 1.45
  const frame = Math.floor(timeSeconds / SHIELD_FRAME_SECONDS) % 2
  context.drawImage(
    image,
    frame * TANK_FRAME_SIZE,
    0,
    TANK_FRAME_SIZE,
    TANK_FRAME_SIZE,
    Math.round(tank.x + (tank.size - size) / 2),
    Math.round(tank.y + (tank.size - size) / 2),
    size,
    size,
  )
}
