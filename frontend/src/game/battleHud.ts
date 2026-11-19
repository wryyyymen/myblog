/** 两种模式共用的战斗状态栏数据，不包含游戏规则。 */
export interface BattleHudPlayer {
  role: 'self' | 'teammate' | 'opponent'
  hp: number
  maxHp: number
  protected: boolean
}

export interface BattleHudState {
  mode: string
  players: BattleHudPlayer[]
  score: number | null
  scoreLabel: string
  defeated: number
  enemyTotal: number
  activeEnemies: number
  baseAlive: boolean | null
  clock: string
}
