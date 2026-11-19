import type { SinglePlayerState } from './singleplayer/engine'

/** 结算页仅保存展示所需的数据，不保存登录凭据或运行中的游戏对象。 */
export interface GameResult {
  mode: 'single' | 'coop' | 'pvp'
  matchId: string
  outcome: 'VICTORY' | 'DEFEAT' | 'DRAW' | 'ABANDONED'
  reason: string
  score: number | null
  defeated: number
  enemyTotal: number
  elapsedSeconds: number
  remainingHp: number
  maxHp: number
  baseAlive: boolean | null
}

function isInteger(value: unknown, minimum: number, maximum: number): value is number {
  return (
    typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
  )
}

/** 刷新、手动修改浏览器存储或版本升级时，拒绝不完整的结算数据。 */
export function readGameResult(value: unknown): GameResult | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const result = value as Record<string, unknown>
  if (
    (result.mode !== 'single' && result.mode !== 'coop' && result.mode !== 'pvp') ||
    typeof result.matchId !== 'string' ||
    result.matchId.trim().length === 0 ||
    result.matchId.length > 96 ||
    (result.outcome !== 'VICTORY' &&
      result.outcome !== 'DEFEAT' &&
      result.outcome !== 'DRAW' &&
      result.outcome !== 'ABANDONED') ||
    typeof result.reason !== 'string' ||
    result.reason.length > 500 ||
    (result.score !== null && !isInteger(result.score, 0, 1_000_000_000)) ||
    !isInteger(result.enemyTotal, 0, 10_000) ||
    !isInteger(result.defeated, 0, result.enemyTotal) ||
    !isInteger(result.elapsedSeconds, 0, 86_400) ||
    !isInteger(result.maxHp, 1, 99) ||
    !isInteger(result.remainingHp, 0, result.maxHp) ||
    (result.baseAlive !== null && typeof result.baseAlive !== 'boolean')
  )
    return null
  return {
    mode: result.mode,
    matchId: result.matchId,
    outcome: result.outcome,
    reason: result.reason,
    score: result.score,
    defeated: result.defeated,
    enemyTotal: result.enemyTotal,
    elapsedSeconds: result.elapsedSeconds,
    remainingHp: result.remainingHp,
    maxHp: result.maxHp,
    baseAlive: result.baseAlive,
  }
}

export function saveGameResult(key: string, result: GameResult): void {
  const checked = readGameResult(result)
  if (checked === null) return
  try {
    sessionStorage.setItem(key, JSON.stringify(checked))
  } catch {
    // 隐私模式或存储空间不足时，当前页面仍可使用内存中的结果。
  }
}

export function loadGameResult(key: string): GameResult | null {
  try {
    const text = sessionStorage.getItem(key)
    if (text === null) return null
    return readGameResult(JSON.parse(text))
  } catch {
    return null
  }
}

export function clearGameResult(key: string): void {
  try {
    sessionStorage.removeItem(key)
  } catch {
    // 清理失败不会阻止重开。
  }
}

export function createSinglePlayerResult(
  state: SinglePlayerState,
  matchId: string,
): GameResult | null {
  if (state.status !== 'won' && state.status !== 'lost') return null
  return {
    mode: 'single',
    matchId,
    outcome: state.status === 'won' ? 'VICTORY' : 'DEFEAT',
    reason: state.result,
    score: state.score,
    defeated: state.kills,
    enemyTotal: state.totalEnemies,
    elapsedSeconds: Math.floor(state.time),
    remainingHp: state.player.hp,
    maxHp: state.player.maxHp,
    baseAlive: state.base.alive,
  }
}
