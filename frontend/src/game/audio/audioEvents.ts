import type { SinglePlayerState } from '../singleplayer/engine'
import type { GameSnapshot } from '../network/protocol'

export type GameSound = 'start' | 'fire' | 'hit' | 'brick' | 'destroy' | 'victory' | 'defeat'

/** 单机引擎会原地修改状态，因此只保存判断音效所需的少量值。 */
export interface SingleAudioFrame {
  status: SinglePlayerState['status']
  hp: number
  kills: number
  baseAlive: boolean
  fireTimes: Map<number, number>
  brickEffects: Set<string>
}

export function captureSingleAudioFrame(state: SinglePlayerState): SingleAudioFrame {
  const fireTimes = new Map<number, number>()
  fireTimes.set(state.player.id, state.player.nextFireAt)
  for (const enemy of state.enemies) fireTimes.set(enemy.id, enemy.nextFireAt)
  const brickEffects = new Set<string>()
  // 单机砖块爆炸半径为一格边长的一半，坦克受击与击毁使用其他半径。
  for (const effect of state.effects) {
    if (effect.radius === state.tileSize / 2)
      brickEffects.add(`${effect.x}:${effect.y}:${effect.expiresAt}`)
  }
  return {
    status: state.status,
    hp: state.player.hp,
    kills: state.kills,
    baseAlive: state.base.alive,
    fireTimes,
    brickEffects,
  }
}

export function singleAudioEvents(
  current: SingleAudioFrame,
  previous?: SingleAudioFrame,
): GameSound[] {
  if (!previous) return current.status === 'playing' ? ['start'] : []
  if (previous.status === 'won' || previous.status === 'lost') return []
  if (current.status === 'won') return ['victory']
  if (current.status === 'lost') return ['defeat']
  if (current.status !== 'playing') return []
  const events: GameSound[] = []
  if (previous.status === 'ready') events.push('start')
  for (const [id, fireTime] of current.fireTimes) {
    const previousFireTime = previous.fireTimes.get(id)
    if (previousFireTime !== undefined && fireTime > previousFireTime) {
      events.push('fire')
      break
    }
  }
  if (current.hp > 0 && current.hp < previous.hp) events.push('hit')
  if (
    current.kills > previous.kills ||
    (current.hp === 0 && previous.hp > 0) ||
    (!current.baseAlive && previous.baseAlive)
  )
    events.push('destroy')
  for (const effect of current.brickEffects) {
    if (!previous.brickEffects.has(effect)) {
      events.push('brick')
      break
    }
  }
  return events
}

/** 同一对局按 tick 去重；大段补同步只恢复画面，不补放离线期间的枪声和爆炸。 */
export function onlineAudioEvents(
  current: GameSnapshot,
  previous: GameSnapshot | undefined,
  userId: number,
): GameSound[] {
  if (!previous || previous.matchId !== current.matchId) {
    return current.status === 'PLAYING' && current.tick <= 2 ? ['start'] : []
  }
  if (current.tick < previous.tick || previous.status === 'FINISHED') return []
  if (current.status === 'FINISHED') {
    if (current.mode === 'COOP') return [current.teamResult === 'VICTORY' ? 'victory' : 'defeat']
    return current.winnerUserId === null
      ? []
      : [current.winnerUserId === userId ? 'victory' : 'defeat']
  }
  if (current.status !== 'PLAYING') return []
  if (previous.status === 'LOADING') return ['start']
  if (current.tick - previous.tick > 8) return []
  const events: GameSound[] = []
  const previousBulletIds = new Set(previous.bullets.map((bullet) => bullet.id))
  if (current.bullets.some((bullet) => !previousBulletIds.has(bullet.id))) events.push('fire')
  for (const tank of current.tanks) {
    const before = previous.tanks.find((item) => item.userId === tank.userId)
    if (before && tank.hp > 0 && tank.hp < before.hp) {
      events.push('hit')
      break
    }
  }
  const playerDestroyed = current.tanks.some(
    (tank) =>
      tank.hp === 0 &&
      previous.tanks.some((before) => before.userId === tank.userId && before.hp > 0),
  )
  if (
    current.enemyDefeated > previous.enemyDefeated ||
    playerDestroyed ||
    (previous.base?.alive === true && current.base?.alive === false)
  )
    events.push('destroy')
  if (current.destroyedCells.length > previous.destroyedCells.length) events.push('brick')
  return events
}
