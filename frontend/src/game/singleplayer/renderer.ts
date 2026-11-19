import type { SinglePlayerState, Tank } from './engine'
import type { SinglePlayerAssets } from './assets'
import {
  BATTLE_COLORS,
  drawTankSprite,
  drawTankHealth,
  drawTankShield,
} from '../rendering/tankRenderer'

const EXPLOSION_FRAME_SIZE = 96

/** 只读取状态，绘制使用项目已有 PNG，不改变位置、血量与碰撞尺寸。 */
export function drawSinglePlayer(
  context: CanvasRenderingContext2D,
  state: SinglePlayerState,
  assets: SinglePlayerAssets,
): void {
  context.save()
  context.setTransform(1, 0, 0, 1, 0, 0)
  context.imageSmoothingEnabled = false
  context.fillStyle = BATTLE_COLORS.battlefield
  context.fillRect(0, 0, state.width, state.height)

  for (let row = 0; row < state.map.length; row++) {
    const cells = state.map[row]
    if (!cells) continue
    for (let column = 0; column < cells.length; column++) {
      const material = cells[column] ?? 0
      // 树冠在坦克之后覆盖绘制，保留树林遮挡效果。
      if (material === 0 || material === 4) continue
      const image =
        material === 3
          ? assets.waterFrames[Math.floor(state.time / 0.3) % 2]
          : assets.tiles[material]
      if (image)
        context.drawImage(
          image,
          column * state.tileSize,
          row * state.tileSize,
          state.tileSize,
          state.tileSize,
        )
    }
  }
  context.drawImage(
    state.base.alive ? assets.base : assets.destroyedBase,
    state.base.x,
    state.base.y,
    state.base.size,
    state.base.size,
  )
  drawTank(context, state.player, state, assets)
  for (const enemy of state.enemies) drawTank(context, enemy, state, assets)

  for (let row = 0; row < state.map.length; row++) {
    const cells = state.map[row]
    if (!cells) continue
    for (let column = 0; column < cells.length; column++) {
      const tree = assets.tiles[4]
      if (cells[column] === 4 && tree) {
        context.drawImage(
          tree,
          column * state.tileSize,
          row * state.tileSize,
          state.tileSize,
          state.tileSize,
        )
      }
    }
  }
  drawTankMarkers(context, state.player, state, assets)
  for (const enemy of state.enemies) drawTankMarkers(context, enemy, state, assets)
  for (const bullet of state.bullets) {
    const size = Math.max(6, bullet.radius * 2)
    context.drawImage(
      assets.bullets[bullet.direction],
      Math.round(bullet.x - size / 2),
      Math.round(bullet.y - size / 2),
      size,
      size,
    )
  }
  for (const effect of state.effects) {
    const remaining = effect.expiresAt - state.time
    if (remaining <= 0) continue
    const progress = Math.max(0, Math.min(1, 1 - remaining / 0.35))
    const frame = Math.min(5, Math.floor(progress * 6))
    const size = Math.max(16, effect.radius * 2)
    context.drawImage(
      assets.explosion,
      frame * EXPLOSION_FRAME_SIZE,
      0,
      EXPLOSION_FRAME_SIZE,
      EXPLOSION_FRAME_SIZE,
      Math.round(effect.x - size / 2),
      Math.round(effect.y - size / 2),
      size,
      size,
    )
  }
  context.restore()
}

function drawTank(
  context: CanvasRenderingContext2D,
  tank: Tank,
  state: SinglePlayerState,
  assets: SinglePlayerAssets,
) {
  if (!tank.alive) return
  const image = tank.team === 'player' ? assets.player : assets.enemy
  drawTankSprite(context, tank, image, state.time)
}

function drawTankMarkers(
  context: CanvasRenderingContext2D,
  tank: Tank,
  state: SinglePlayerState,
  assets: SinglePlayerAssets,
) {
  if (!tank.alive) return
  drawTankHealth(context, tank, tank.hp, tank.maxHp, tank.team)
  if (tank.team === 'player' && state.time < tank.shieldUntil) {
    drawTankShield(context, tank, assets.shield, state.time)
  }
}
