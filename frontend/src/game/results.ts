import type { SinglePlayerState } from './singleplayer/engine'

/** 结算页仅保存展示所需的数据，不保存登录凭据或运行中的游戏对象。 */
export interface GameResult {
  levelId?: number
  levelName?: string
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
  /** 写入结算记录时的历史最佳分，仅单人闯关带此字段。 */
  bestScore?: number
  /** 本局是否刷新了该关最佳分。 */
  isNewRecord?: boolean
}

/** 单个关卡的历史最佳成绩，只存在浏览器本地，不上传服务端。 */
export interface BestScore {
  levelId: number
  score: number
  defeated: number
  elapsedSeconds: number
  achievedAt: string
}

/** 最佳成绩存 localStorage：刷新和关掉浏览器都要保留。 */
const BEST_SCORES_KEY = 'tank:best-scores'

function isInteger(value: unknown, minimum: number, maximum: number): value is number {
  return (
    typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
  )
}

/** 刷新、手动修改浏览器存储或版本升级时，拒绝不完整的结算数据。 */
export function readGameResult(value: unknown): GameResult | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const result = value as Record<string, unknown>
  const hasLevel = result.levelId !== undefined || result.levelName !== undefined
  if (
    hasLevel &&
    (!isInteger(result.levelId, 1, 5) ||
      typeof result.levelName !== 'string' ||
      result.levelName.length === 0 ||
      result.levelName.length > 100)
  )
    return null
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
    (result.baseAlive !== null && typeof result.baseAlive !== 'boolean') ||
    (result.bestScore !== undefined && !isInteger(result.bestScore, 0, 1_000_000_000)) ||
    (result.isNewRecord !== undefined && typeof result.isNewRecord !== 'boolean')
  )
    return null
  return {
    ...(hasLevel
      ? { levelId: result.levelId as number, levelName: result.levelName as string }
      : {}),
    ...(result.bestScore !== undefined ? { bestScore: result.bestScore as number } : {}),
    ...(result.isNewRecord !== undefined ? { isNewRecord: result.isNewRecord as boolean } : {}),
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

/** 校验单条最佳成绩，字段缺失或超范围一律拒绝。 */
export function readBestScore(value: unknown): BestScore | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  if (
    !isInteger(record.levelId, 1, 5) ||
    !isInteger(record.score, 0, 1_000_000_000) ||
    !isInteger(record.defeated, 0, 10_000) ||
    !isInteger(record.elapsedSeconds, 0, 86_400) ||
    typeof record.achievedAt !== 'string' ||
    record.achievedAt.length === 0 ||
    record.achievedAt.length > 40
  )
    return null
  return {
    levelId: record.levelId,
    score: record.score,
    defeated: record.defeated,
    elapsedSeconds: record.elapsedSeconds,
    achievedAt: record.achievedAt,
  }
}

/**
 * 校验整份最佳成绩表。
 *
 * 用关卡号做键，坏掉的那一条直接丢弃，其余仍然可用：
 * 某次版本升级写坏了第 3 关，不该让第 1、2 关的记录一起消失。
 */
export function readBestScores(value: unknown): Record<number, BestScore> {
  const scores: Record<number, BestScore> = {}
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return scores
  for (const entry of Object.values(value as Record<string, unknown>)) {
    const checked = readBestScore(entry)
    if (checked !== null) scores[checked.levelId] = checked
  }
  return scores
}

export function loadBestScores(): Record<number, BestScore> {
  try {
    const text = localStorage.getItem(BEST_SCORES_KEY)
    if (text === null) return {}
    return readBestScores(JSON.parse(text))
  } catch {
    // 隐私模式、存储被禁用或内容损坏时，按“还没有记录”处理。
    return {}
  }
}

export function saveBestScores(scores: Record<number, BestScore>): void {
  try {
    localStorage.setItem(BEST_SCORES_KEY, JSON.stringify(scores))
  } catch {
    // 存不进去只影响下次进入时的展示，本局结算页仍然正常。
  }
}

export function clearBestScores(): void {
  try {
    localStorage.removeItem(BEST_SCORES_KEY)
  } catch {
    // 清理失败不影响游戏。
  }
}

/**
 * 判断新成绩是否优于旧成绩。
 *
 * 先比分数，分数相同再比用时：同样打满 8 辆敌军，用 40 秒的比用 90 秒的强。
 * 两个都一样就不算新纪录，避免每局都弹「新纪录」。
 */
export function isBetterScore(
  next: { score: number; elapsedSeconds: number },
  current: { score: number; elapsedSeconds: number } | undefined,
): boolean {
  if (current === undefined) return true
  if (next.score !== current.score) return next.score > current.score
  return next.elapsedSeconds < current.elapsedSeconds
}

/**
 * 把一局结果并入最佳成绩表。
 *
 * 只有单人闯关的胜利才参与比较：失败局分数通常更低，记下来没有意义；
 * 联机结果不由本地浏览器负责保管。
 *
 * @returns 该关最新的最佳成绩和本局是否刷新纪录；不参与比较时返回 null
 */
export function updateBestScore(
  result: GameResult,
): { best: BestScore; isNewRecord: boolean } | null {
  if (result.mode !== 'single' || result.outcome !== 'VICTORY') return null
  if (result.levelId === undefined || result.score === null) return null

  const scores = loadBestScores()
  const candidate = {
    score: result.score,
    elapsedSeconds: result.elapsedSeconds,
  }

  if (!isBetterScore(candidate, scores[result.levelId])) {
    return { best: scores[result.levelId]!, isNewRecord: false }
  }

  const best: BestScore = {
    levelId: result.levelId,
    score: result.score,
    defeated: result.defeated,
    elapsedSeconds: result.elapsedSeconds,
    achievedAt: new Date().toISOString(),
  }
  scores[result.levelId] = best
  saveBestScores(scores)
  return { best, isNewRecord: true }
}

export function createSinglePlayerResult(
  state: SinglePlayerState,
  matchId: string,
): GameResult | null {
  if (state.status !== 'won' && state.status !== 'lost') return null
  return {
    mode: 'single',
    ...(state.levelId !== undefined ? { levelId: state.levelId, levelName: state.levelName } : {}),
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
