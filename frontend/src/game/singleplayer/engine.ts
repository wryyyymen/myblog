import { createLevelMap, gameMapSettings, getGameLevel } from '../maps/catalog'

/** 单人 PVE 规则。与 Vue、Canvas 和网络分开，便于独立验证。 */
export type Direction = 0 | 1 | 2 | 3
export type Team = 'player' | 'enemy'
export type GameStatus = 'ready' | 'playing' | 'paused' | 'won' | 'lost'

interface Cell {
  row: number
  column: number
}
export interface Tank {
  id: number
  team: Team
  x: number
  y: number
  size: number
  direction: Direction
  hp: number
  maxHp: number
  alive: boolean
  shieldUntil: number
  nextFireAt: number
  thinkAt: number
  stuckFor: number
  path: Cell[]
}
export interface Bullet {
  id: number
  team: Team
  x: number
  y: number
  direction: Direction
  radius: number
  speed: number
  ownerId: number
}
export interface Effect {
  x: number
  y: number
  radius: number
  expiresAt: number
}
export interface SinglePlayerState {
  levelId: number
  levelName: string
  maxAliveEnemies: number
  enemySpeed: number
  spawnIntervalSeconds: number
  status: GameStatus
  map: number[][]
  tileSize: number
  width: number
  height: number
  player: Tank
  enemies: Tank[]
  bullets: Bullet[]
  effects: Effect[]
  base: { x: number; y: number; size: number; alive: boolean }
  time: number
  score: number
  kills: number
  totalEnemies: number
  spawnedEnemies: number
  nextSpawnAt: number
  nextId: number
  randomSeed: number
  result: string
}
export interface PlayerControls {
  direction: Direction | null
  fireHeld: boolean
}

export const FIXED_STEP = 1 / 60
const TILE = gameMapSettings.tileSize
const BOARD_SIZE = 26
const PLAYER_SPEED = 112
const EPSILON = 0.00001

function passable(material: number | undefined) {
  return material === 0 || material === 4 || material === 5
}

function tank(id: number, team: Team, row: number, column: number): Tank {
  const size = TILE * 0.8
  return {
    id,
    team,
    x: column * TILE + (TILE - size) / 2,
    y: row * TILE + (TILE - size) / 2,
    size,
    direction: team === 'player' ? 0 : 1,
    hp: team === 'player' ? 3 : 1,
    maxHp: team === 'player' ? 3 : 1,
    alive: true,
    shieldUntil: team === 'player' ? 3 : 0,
    nextFireAt: 0,
    thinkAt: 0,
    stuckFor: 0,
    path: [],
  }
}

/** 保留seed作为第一个参数；第二个参数选择关卡，旧调用默认进入第一关。 */
export function createSinglePlayerGame(seed = Date.now(), levelId = 1): SinglePlayerState {
  const level = getGameLevel(levelId)
  const playerSpawn = gameMapSettings.playerSpawns[0]
  if (!playerSpawn) throw new Error('地图未配置单人出生点')
  return {
    levelId: level.id,
    levelName: level.name,
    maxAliveEnemies: level.maxAliveEnemies,
    enemySpeed: level.enemySpeed,
    spawnIntervalSeconds: level.spawnIntervalMillis / 1000,
    status: 'ready',
    map: createLevelMap(level.id),
    tileSize: TILE,
    width: BOARD_SIZE * TILE,
    height: BOARD_SIZE * TILE,
    player: tank(1, 'player', playerSpawn.row, playerSpawn.column),
    enemies: [],
    bullets: [],
    effects: [],
    base: {
      x: gameMapSettings.base.column * TILE,
      y: gameMapSettings.base.row * TILE,
      size: TILE,
      alive: true,
    },
    time: 0,
    score: 0,
    kills: 0,
    totalEnemies: level.enemyTotal,
    spawnedEnemies: 0,
    nextSpawnAt: 0,
    nextId: 2,
    randomSeed: seed >>> 0,
    result: '',
  }
}

export function startSinglePlayerGame(state: SinglePlayerState) {
  if (state.status === 'ready') state.status = 'playing'
}

export function pauseSinglePlayerGame(state: SinglePlayerState) {
  if (state.status === 'playing') state.status = 'paused'
  else if (state.status === 'paused') state.status = 'playing'
}

function random(state: SinglePlayerState): number {
  state.randomSeed = (Math.imul(state.randomSeed, 1664525) + 1013904223) >>> 0
  return state.randomSeed / 4294967296
}

function overlaps(
  x: number,
  y: number,
  size: number,
  other: { x: number; y: number; size: number },
) {
  return (
    x < other.x + other.size - EPSILON &&
    x + size > other.x + EPSILON &&
    y < other.y + other.size - EPSILON &&
    y + size > other.y + EPSILON
  )
}

function allTanks(state: SinglePlayerState): Tank[] {
  return [state.player, ...state.enemies]
}

/** 截短到前方最近障碍，既检查终点也检查经过的整段位置。 */
function moveTank(
  state: SinglePlayerState,
  current: Tank,
  direction: Direction,
  distance: number,
): number {
  current.direction = direction
  let allowed = distance
  if (direction === 0) allowed = Math.min(allowed, current.y)
  if (direction === 1) allowed = Math.min(allowed, state.height - current.y - current.size)
  if (direction === 2) allowed = Math.min(allowed, current.x)
  if (direction === 3) allowed = Math.min(allowed, state.width - current.x - current.size)
  function limit(x: number, y: number, size: number) {
    const right = current.x + current.size
    const bottom = current.y + current.size
    const sharedX = current.x < x + size - EPSILON && right > x + EPSILON
    const sharedY = current.y < y + size - EPSILON && bottom > y + EPSILON
    if (direction === 0 && sharedX && y + size <= current.y + EPSILON)
      allowed = Math.min(allowed, Math.max(0, current.y - y - size))
    if (direction === 1 && sharedX && y >= bottom - EPSILON)
      allowed = Math.min(allowed, Math.max(0, y - bottom))
    if (direction === 2 && sharedY && x + size <= current.x + EPSILON)
      allowed = Math.min(allowed, Math.max(0, current.x - x - size))
    if (direction === 3 && sharedY && x >= right - EPSILON)
      allowed = Math.min(allowed, Math.max(0, x - right))
  }
  const firstRow = Math.max(0, Math.floor((current.y - distance) / TILE))
  const lastRow = Math.min(BOARD_SIZE - 1, Math.floor((current.y + current.size + distance) / TILE))
  const firstColumn = Math.max(0, Math.floor((current.x - distance) / TILE))
  const lastColumn = Math.min(
    BOARD_SIZE - 1,
    Math.floor((current.x + current.size + distance) / TILE),
  )
  for (let row = firstRow; row <= lastRow; row++) {
    for (let column = firstColumn; column <= lastColumn; column++) {
      if (!passable(state.map[row]?.[column])) limit(column * TILE, row * TILE, TILE)
    }
  }
  if (state.base.alive) limit(state.base.x, state.base.y, state.base.size)
  for (const other of allTanks(state))
    if (other.alive && other.id !== current.id) limit(other.x, other.y, other.size)
  if (direction === 0) current.y -= allowed
  else if (direction === 1) current.y += allowed
  else if (direction === 2) current.x -= allowed
  else current.x += allowed
  return allowed
}

function spawnEnemy(state: SinglePlayerState) {
  if (
    state.spawnedEnemies >= state.totalEnemies ||
    state.enemies.length >= state.maxAliveEnemies ||
    state.time < state.nextSpawnAt
  )
    return
  const spawns = gameMapSettings.enemySpawns
  for (let attempt = 0; attempt < spawns.length; attempt++) {
    const spawn = spawns[(state.spawnedEnemies + attempt) % spawns.length]
    if (!spawn) continue
    const enemy = tank(state.nextId, 'enemy', spawn.row, spawn.column)
    if (
      allTanks(state).some((other) => other.alive && overlaps(enemy.x, enemy.y, enemy.size, other))
    )
      continue
    enemy.nextFireAt = state.time + 0.8 + random(state)
    state.enemies.push(enemy)
    state.nextId++
    state.spawnedEnemies++
    state.nextSpawnAt = state.time + state.spawnIntervalSeconds
    return
  }
  state.nextSpawnAt = state.time + 0.3
}

function fire(state: SinglePlayerState, current: Tank) {
  if (!current.alive || state.time < current.nextFireAt) return
  const limit = current.team === 'player' ? 3 : 1
  if (state.bullets.filter((bullet) => bullet.ownerId === current.id).length >= limit) return
  const radius = 2.5
  let x = current.x + current.size / 2
  let y = current.y + current.size / 2
  const offset = current.size / 2 + radius
  if (current.direction === 0) y -= offset
  else if (current.direction === 1) y += offset
  else if (current.direction === 2) x -= offset
  else x += offset
  state.bullets.push({
    id: state.nextId++,
    team: current.team,
    ownerId: current.id,
    x,
    y,
    radius,
    direction: current.direction,
    speed: current.team === 'player' ? 320 : 230,
  })
  current.nextFireAt = state.time + (current.team === 'player' ? 0.3 : 1.25 + random(state) * 0.5)
}

function pathTo(state: SinglePlayerState, current: Tank, goal: Cell): Cell[] {
  const start: Cell = {
    row: Math.floor((current.y + current.size / 2) / TILE),
    column: Math.floor((current.x + current.size / 2) / TILE),
  }
  const key = (cell: Cell) => cell.row * BOARD_SIZE + cell.column
  const queue: Cell[] = [start]
  const parents = new Map<number, Cell | null>([[key(start), null]])
  let found: Cell | null = null
  for (let index = 0; index < queue.length; index++) {
    const cell = queue[index]
    if (!cell) continue
    if (cell.row === goal.row && cell.column === goal.column) {
      found = cell
      break
    }
    const options: Cell[] = [
      { row: cell.row + 1, column: cell.column },
      { row: cell.row, column: cell.column - 1 },
      { row: cell.row, column: cell.column + 1 },
      { row: cell.row - 1, column: cell.column },
    ]
    for (const next of options) {
      if (
        parents.has(key(next)) ||
        next.row <= 0 ||
        next.row >= BOARD_SIZE - 1 ||
        next.column <= 0 ||
        next.column >= BOARD_SIZE - 1
      )
        continue
      if (!passable(state.map[next.row]?.[next.column])) continue
      if (
        state.base.alive &&
        next.row === state.base.y / TILE &&
        next.column === state.base.x / TILE
      )
        continue
      const x = next.column * TILE + (TILE - current.size) / 2
      const y = next.row * TILE + (TILE - current.size) / 2
      if (
        state.enemies.some(
          (other) => other.id !== current.id && other.alive && overlaps(x, y, current.size, other),
        )
      )
        continue
      parents.set(key(next), cell)
      queue.push(next)
    }
  }
  if (found === null) return []
  const path: Cell[] = []
  let cursor: Cell | null = found
  while (cursor !== null && key(cursor) !== key(start)) {
    path.push(cursor)
    cursor = parents.get(key(cursor)) ?? null
  }
  path.reverse()
  return path
}

function aimAt(
  state: SinglePlayerState,
  current: Tank,
  targetX: number,
  targetY: number,
): Direction | null {
  const x = current.x + current.size / 2
  const y = current.y + current.size / 2
  let direction: Direction
  if (Math.abs(x - targetX) < 8 && Math.abs(y - targetY) > 12) direction = targetY > y ? 1 : 0
  else if (Math.abs(y - targetY) < 8 && Math.abs(x - targetX) > 12) direction = targetX > x ? 3 : 2
  else return null
  // 钢墙不能破坏，不能站在钢墙后一直向目标空射；砖墙可以开火打通。
  const length = Math.max(Math.abs(x - targetX), Math.abs(y - targetY))
  for (let offset = 12; offset < length; offset += 8) {
    const sampleX = x + (direction === 3 ? offset : direction === 2 ? -offset : 0)
    const sampleY = y + (direction === 1 ? offset : direction === 0 ? -offset : 0)
    if (state.map[Math.floor(sampleY / TILE)]?.[Math.floor(sampleX / TILE)] === 2) return null
  }
  return direction
}

function updateEnemy(state: SinglePlayerState, enemy: Tank, delta: number) {
  let aim: Direction | null = null
  const playerDistance = Math.hypot(enemy.x - state.player.x, enemy.y - state.player.y)
  if (state.player.alive && playerDistance < 290) {
    aim = aimAt(
      state,
      enemy,
      state.player.x + state.player.size / 2,
      state.player.y + state.player.size / 2,
    )
  }
  if (aim === null && enemy.id % 3 !== 0 && state.base.alive) {
    aim = aimAt(state, enemy, state.base.x + TILE / 2, state.base.y + TILE / 2)
  }
  if (aim !== null) {
    enemy.direction = aim
    fire(state, enemy)
    return
  }

  const currentCell: Cell = {
    row: Math.floor((enemy.y + enemy.size / 2) / TILE),
    column: Math.floor((enemy.x + enemy.size / 2) / TILE),
  }
  const centerX = currentCell.column * TILE + (TILE - enemy.size) / 2
  const centerY = currentCell.row * TILE + (TILE - enemy.size) / 2
  const centered = Math.abs(enemy.x - centerX) < 0.05 && Math.abs(enemy.y - centerY) < 0.05
  if (enemy.path.length === 0 && !centered) {
    enemy.path = [currentCell]
  } else if (centered && (state.time >= enemy.thinkAt || enemy.path.length === 0)) {
    const goal: Cell =
      enemy.id % 3 === 0
        ? {
            row: Math.floor((state.player.y + state.player.size / 2) / TILE),
            column: Math.floor((state.player.x + state.player.size / 2) / TILE),
          }
        : { row: state.base.y / TILE - 2, column: state.base.x / TILE }
    enemy.path = pathTo(state, enemy, goal)
    enemy.thinkAt = state.time + 0.85
  }
  const next = enemy.path[0]
  let direction = enemy.direction
  let distance = state.enemySpeed * delta
  if (next) {
    const destinationX = next.column * TILE + (TILE - enemy.size) / 2
    const destinationY = next.row * TILE + (TILE - enemy.size) / 2
    if (Math.abs(destinationX - enemy.x) > 0.05) {
      direction = destinationX > enemy.x ? 3 : 2
      distance = Math.min(distance, Math.abs(destinationX - enemy.x))
    } else if (Math.abs(destinationY - enemy.y) > 0.05) {
      direction = destinationY > enemy.y ? 1 : 0
      distance = Math.min(distance, Math.abs(destinationY - enemy.y))
    } else {
      enemy.path.shift()
      return
    }
  }
  const moved = moveTank(state, enemy, direction, distance)
  enemy.stuckFor = moved < 0.01 ? enemy.stuckFor + delta : 0
  if (enemy.stuckFor > 0.45) {
    fire(state, enemy)
    enemy.path = []
    enemy.thinkAt = 0
    if (enemy.stuckFor > 1) {
      enemy.direction = Math.floor(random(state) * 4) as Direction
      enemy.stuckFor = 0
    }
  } else if (random(state) < delta * 0.45) fire(state, enemy)
}

function hitDistance(bullet: Bullet, rectangle: { x: number; y: number; size: number }): number {
  const left = rectangle.x - bullet.radius
  const right = rectangle.x + rectangle.size + bullet.radius
  const top = rectangle.y - bullet.radius
  const bottom = rectangle.y + rectangle.size + bullet.radius
  if (bullet.direction === 0) {
    if (bullet.x < left || bullet.x > right || bullet.y < top) return Infinity
    return Math.max(0, bullet.y - bottom)
  }
  if (bullet.direction === 1) {
    if (bullet.x < left || bullet.x > right || bullet.y > bottom) return Infinity
    return Math.max(0, top - bullet.y)
  }
  if (bullet.direction === 2) {
    if (bullet.y < top || bullet.y > bottom || bullet.x < left) return Infinity
    return Math.max(0, bullet.x - right)
  }
  if (bullet.y < top || bullet.y > bottom || bullet.x > right) return Infinity
  return Math.max(0, left - bullet.x)
}

function explosion(state: SinglePlayerState, x: number, y: number, radius: number) {
  state.effects.push({ x, y, radius, expiresAt: state.time + 0.35 })
}

function advanceBullets(state: SinglePlayerState, delta: number) {
  const remaining: Bullet[] = []
  for (const bullet of state.bullets) {
    let nearest = bullet.speed * delta
    let collision = false
    let wall: Cell | null = null
    let target: Tank | null = null
    let baseHit = false
    const reach = nearest + bullet.radius
    const minRow = Math.max(0, Math.floor((bullet.y - reach) / TILE))
    const maxRow = Math.min(BOARD_SIZE - 1, Math.floor((bullet.y + reach) / TILE))
    const minColumn = Math.max(0, Math.floor((bullet.x - reach) / TILE))
    const maxColumn = Math.min(BOARD_SIZE - 1, Math.floor((bullet.x + reach) / TILE))
    for (let row = minRow; row <= maxRow; row++) {
      for (let column = minColumn; column <= maxColumn; column++) {
        const material = state.map[row]?.[column]
        if (material !== 1 && material !== 2) continue
        const distance = hitDistance(bullet, { x: column * TILE, y: row * TILE, size: TILE })
        if (distance <= nearest && (!collision || distance < nearest - EPSILON)) {
          nearest = distance
          collision = true
          wall = { row, column }
        }
      }
    }
    for (const current of allTanks(state)) {
      if (!current.alive || current.team === bullet.team) continue
      const distance = hitDistance(bullet, current)
      if (distance <= nearest && (!collision || distance < nearest - EPSILON)) {
        nearest = distance
        collision = true
        wall = null
        target = current
        baseHit = false
      }
    }
    if (bullet.team === 'enemy' && state.base.alive) {
      const distance = hitDistance(bullet, state.base)
      if (distance <= nearest && (!collision || distance < nearest - EPSILON)) {
        nearest = distance
        collision = true
        wall = null
        target = null
        baseHit = true
      }
    }
    const dx = bullet.direction === 3 ? nearest : bullet.direction === 2 ? -nearest : 0
    const dy = bullet.direction === 1 ? nearest : bullet.direction === 0 ? -nearest : 0
    bullet.x += dx
    bullet.y += dy
    if (collision) {
      if (wall && state.map[wall.row]?.[wall.column] === 1) {
        const row = state.map[wall.row]
        if (row) row[wall.column] = 0
        explosion(state, wall.column * TILE + TILE / 2, wall.row * TILE + TILE / 2, 12)
      }
      if (target && state.time >= target.shieldUntil) {
        target.hp = Math.max(0, target.hp - 1)
        target.alive = target.hp > 0
        explosion(
          state,
          target.x + target.size / 2,
          target.y + target.size / 2,
          target.alive ? 13 : 25,
        )
        if (target.team === 'player') target.shieldUntil = state.time + 0.9
        else if (!target.alive) {
          state.kills++
          state.score += 100
        }
      }
      if (baseHit) {
        state.base.alive = false
        explosion(state, state.base.x + TILE / 2, state.base.y + TILE / 2, 32)
      }
      continue
    }
    if (bullet.x > 0 && bullet.x < state.width && bullet.y > 0 && bullet.y < state.height)
      remaining.push(bullet)
  }
  state.bullets = remaining
}

/** 页面以固定1/60秒调用；暂停、结束后不继续计算战斗。 */
export function stepSinglePlayerGame(
  state: SinglePlayerState,
  input: PlayerControls,
  delta = FIXED_STEP,
) {
  if (state.status !== 'playing') return
  const elapsed = Math.max(0, Math.min(delta, 0.05))
  state.time += elapsed
  state.effects = state.effects.filter((effect) => effect.expiresAt > state.time)
  spawnEnemy(state)
  if (state.player.alive && input.direction !== null)
    moveTank(state, state.player, input.direction, PLAYER_SPEED * elapsed)
  if (input.fireHeld) fire(state, state.player)
  for (const enemy of state.enemies) if (enemy.alive) updateEnemy(state, enemy, elapsed)
  advanceBullets(state, elapsed)
  state.enemies = state.enemies.filter((enemy) => enemy.alive)
  if (!state.base.alive) {
    state.status = 'lost'
    state.result = '基地被摧毁了'
  } else if (!state.player.alive) {
    state.status = 'lost'
    state.result = '你的坦克被击毁了'
  } else if (state.kills >= state.totalEnemies) {
    state.status = 'won'
    state.result = '全部敌军已消灭，基地守住了！'
  }
}
