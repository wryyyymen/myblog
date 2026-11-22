import catalogData from '../../../../backend/src/main/resources/game/maps/levels.json'

export interface MapCell {
  readonly row: number
  readonly column: number
}

/** 关卡模板只读；战斗时用 createLevelMap 取得可破坏的独立地图。 */
export interface GameLevel {
  readonly id: number
  readonly name: string
  readonly description: string
  readonly difficulty: string
  readonly enemyTotal: number
  readonly maxAliveEnemies: number
  readonly enemySpeed: number
  readonly spawnIntervalMillis: number
  readonly rows: readonly string[]
}

export interface GameMapSettings {
  readonly tileSize: number
  readonly base: MapCell
  readonly playerSpawns: readonly MapCell[]
  readonly enemySpawns: readonly MapCell[]
}

const BOARD_SIZE = 26

function configurationError(message: string): never {
  throw new Error(`地图配置错误：${message}`)
}

function readObject(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    configurationError(`${label}必须是对象`)
  return value as Record<string, unknown>
}

function readInteger(value: unknown, minimum: number, maximum: number, label: string): number {
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    value < minimum ||
    value > maximum
  )
    configurationError(`${label}必须是 ${minimum} 至 ${maximum} 的整数`)
  return value
}

function readText(value: unknown, label: string): string {
  if (typeof value !== 'string' || value.trim().length === 0) configurationError(`${label}不能为空`)
  return value
}

function readSpeed(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0 || value > 120)
    configurationError(`${label}必须大于0且不超过120`)
  return value
}

function readCell(value: unknown, label: string): MapCell {
  const cell = readObject(value, label)
  return Object.freeze({
    row: readInteger(cell.row, 1, BOARD_SIZE - 2, `${label}.row`),
    column: readInteger(cell.column, 1, BOARD_SIZE - 2, `${label}.column`),
  })
}

function readCells(value: unknown, count: number, label: string): readonly MapCell[] {
  if (!Array.isArray(value) || value.length !== count)
    configurationError(`${label}必须包含 ${count} 个出生点`)
  const cells: MapCell[] = []
  const usedCells = new Set<string>()
  for (let index = 0; index < value.length; index++) {
    const cell = readCell(value[index], `${label}[${index}]`)
    const key = `${cell.row},${cell.column}`
    if (usedCells.has(key)) configurationError(`${label}存在重复出生点`)
    usedCells.add(key)
    cells.push(cell)
  }
  return Object.freeze(cells)
}

function readRows(value: unknown, label: string): readonly string[] {
  if (!Array.isArray(value) || value.length !== BOARD_SIZE)
    configurationError(`${label}必须有 ${BOARD_SIZE} 行`)
  const rows: string[] = []
  for (let index = 0; index < value.length; index++) {
    const row = value[index]
    if (typeof row !== 'string' || !/^[0-5]{26}$/.test(row))
      configurationError(`${label}[${index}]必须有26个材质编号（0至5）`)
    for (let column = 0; column < BOARD_SIZE; column++) {
      const boundary =
        index === 0 || index === BOARD_SIZE - 1 || column === 0 || column === BOARD_SIZE - 1
      if (boundary && row[column] !== '2') configurationError(`${label}边界必须为钢墙`)
    }
    rows.push(row)
  }
  return Object.freeze(rows)
}

function readCatalog(data: unknown): { settings: GameMapSettings; levels: readonly GameLevel[] } {
  const source = readObject(data, '目录')
  if (source.version !== 1) configurationError('不支持的目录版本')
  // 当前移动和碰撞按24像素、26×26网格计算；修改尺寸前必须同步引擎。
  if (source.tileSize !== 24) configurationError('当前仅支持24像素格子')
  const settings: GameMapSettings = Object.freeze({
    tileSize: source.tileSize,
    base: readCell(source.base, '基地'),
    playerSpawns: readCells(source.playerSpawns, 2, '玩家出生点'),
    enemySpawns: readCells(source.enemySpawns, 3, '敌军出生点'),
  })
  const reservedCells = [settings.base, ...settings.playerSpawns, ...settings.enemySpawns]
  const reservedKeys = new Set<string>()
  for (const cell of reservedCells) {
    const key = `${cell.row},${cell.column}`
    if (reservedKeys.has(key)) configurationError('基地、玩家与敌军出生点不能重叠')
    reservedKeys.add(key)
  }
  if (!Array.isArray(source.levels) || source.levels.length !== 5)
    configurationError('当前目录必须包含5个关卡')
  const levels: GameLevel[] = []
  for (const value of source.levels) {
    const sourceLevel = readObject(value, '关卡')
    const id = readInteger(sourceLevel.id, 1, 5, '关卡编号')
    if (id !== levels.length + 1) configurationError('关卡编号必须按1至5排序')
    const enemyTotal = readInteger(sourceLevel.enemyTotal, 1, 30, `第${id}关敌军总数`)
    const spawnIntervalMillis = readInteger(
      sourceLevel.spawnIntervalMillis,
      50,
      10_000,
      `第${id}关出兵间隔`,
    )
    if (spawnIntervalMillis % 50 !== 0) configurationError(`第${id}关出兵间隔必须是50毫秒的倍数`)
    const level: GameLevel = Object.freeze({
      id,
      name: readText(sourceLevel.name, `第${id}关名称`),
      description: readText(sourceLevel.description, `第${id}关介绍`),
      difficulty: readText(sourceLevel.difficulty, `第${id}关难度`),
      enemyTotal,
      maxAliveEnemies: readInteger(
        sourceLevel.maxAliveEnemies,
        1,
        Math.min(8, enemyTotal),
        `第${id}关同时在场敌军`,
      ),
      enemySpeed: readSpeed(sourceLevel.enemySpeed, `第${id}关敌军速度`),
      spawnIntervalMillis,
      rows: readRows(sourceLevel.rows, `第${id}关地图`),
    })
    for (const cell of reservedCells) {
      if (level.rows[cell.row]?.[cell.column] !== '0')
        configurationError(`第${id}关基地或出生点被地形占用`)
    }
    levels.push(level)
  }
  return { settings, levels: Object.freeze(levels) }
}

// JSON同时作为Java类路径资源；Vite将这份数据打进前端，单机无需请求后端。
const catalog = readCatalog(catalogData)
export const gameMapSettings = catalog.settings
export const gameLevels = catalog.levels

export function getGameLevel(levelId = 1): GameLevel {
  for (const level of gameLevels) {
    if (level.id === levelId) return level
  }
  throw new RangeError(`不存在第 ${levelId} 关`)
}

export function createLevelMap(levelId = 1): number[][] {
  const level = getGameLevel(levelId)
  const map: number[][] = []
  for (const row of level.rows) {
    const cells: number[] = []
    for (const material of row) cells.push(Number(material))
    map.push(cells)
  }
  return map
}
