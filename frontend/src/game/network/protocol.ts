import type { GameMapData } from '../mapLoader'
import type { PlayerState } from '../playRenderer'

export type Direction = PlayerState['direction']
export type RoomStatus = 'WAITING' | 'LOADING' | 'PLAYING' | 'FINISHED'

export interface RoomSnapshot {
  mode: 'PVP' | 'COOP'
  roomId: string
  ownerUserId: number
  memberUserIds: number[]
  readyUserIds: number[]
  connectedUserIds: number[]
  version: number
  status: RoomStatus
  matchId: string | null
}

export interface TankSnapshot extends PlayerState {
  userId: number
  lastInputSeq: number
  hp: number
  shieldUntilTick: number
}

export interface AvailableRoom {
  roomId: string
  ownerUserId: number
  memberCount: number
  maxMembers: number
  mode: 'COOP' | 'PVP'
  version: number
}

export interface BulletSnapshot {
  id: number
  ownerUserId: number
  x: number
  y: number
  direction: Direction
}

export interface GameSnapshot {
  mode: 'PVP' | 'COOP'
  enemies: EnemySnapshot[]
  base: BaseSnapshot | null
  enemyTotal: number
  enemySpawned: number
  enemyDefeated: number
  teamResult: 'VICTORY' | 'DEFEAT' | 'ABANDONED' | null
  roomId: string
  matchId: string
  status: 'LOADING' | 'PLAYING' | 'FINISHED'
  tick: number
  serverTime: number
  tanks: TankSnapshot[]
  bullets: BulletSnapshot[]
  destroyedCells: number[]
  winnerUserId: number | null
  endReason: string | null
}

export interface EnemySnapshot extends PlayerState {
  id: number
  hp: number
  maxHp: number
}

export interface BaseSnapshot {
  x: number
  y: number
  size: number
  hp: number
  alive: boolean
}

export interface GameInitialization {
  roomId: string
  matchId: string
  roomVersion: number
  map: GameMapData
  rules: {
    moveSpeed: number
    tickMillis: number
    inputTimeoutMillis: number
    initialHp: number
    bulletSpeed: number
    fireCooldownMillis: number
    matchDurationMillis: number
  }
  state: GameSnapshot
}

export interface RoomDeparture {
  roomId: string
  version: number
}

export interface GameEnding {
  roomId: string
  matchId: string
  reason: string
  leavingUserId: number | null
  winnerUserId: number | null
  state: GameSnapshot
}

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= 64
}

function isInteger(value: unknown, minimum: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum
}

function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

function readUserIds(value: unknown): number[] | null {
  if (!Array.isArray(value) || value.length > 2) return null
  const result: number[] = []
  for (const userId of value) {
    if (!isInteger(userId, 1) || result.includes(userId)) return null
    result.push(userId)
  }
  return result
}

/** 大厅只接收可加入房间的公开摘要，不使用它替代服务端加入校验。 */
export function readAvailableRooms(value: unknown): AvailableRoom[] | null {
  if (!Array.isArray(value) || value.length > 100) return null
  const rooms: AvailableRoom[] = []
  for (const item of value) {
    if (
      !isObject(item) ||
      !isIdentifier(item.roomId) ||
      !isInteger(item.ownerUserId, 1) ||
      !isInteger(item.version, 1) ||
      item.memberCount !== 1 ||
      item.maxMembers !== 2 ||
      (item.mode !== 'COOP' && item.mode !== 'PVP') ||
      rooms.some((room) => room.roomId === item.roomId)
    )
      return null
    rooms.push({
      roomId: item.roomId,
      ownerUserId: item.ownerUserId,
      memberCount: item.memberCount,
      maxMembers: item.maxMembers,
      mode: item.mode,
      version: item.version,
    })
  }
  return rooms
}

/** 先检查未知 JSON，再把通过校验的字段复制为页面使用的对象。 */
export function readRoom(value: unknown): RoomSnapshot | null {
  if (!isObject(value)) return null
  const members = readUserIds(value.memberUserIds)
  const ready = readUserIds(value.readyUserIds)
  const connected = readUserIds(value.connectedUserIds)
  if (
    (value.mode !== 'PVP' && value.mode !== 'COOP') ||
    !isIdentifier(value.roomId) ||
    !isInteger(value.ownerUserId, 1) ||
    !isInteger(value.version, 1) ||
    members === null ||
    members.length === 0 ||
    ready === null ||
    connected === null ||
    !members.includes(value.ownerUserId) ||
    (value.status !== 'WAITING' &&
      value.status !== 'LOADING' &&
      value.status !== 'PLAYING' &&
      value.status !== 'FINISHED') ||
    (value.matchId !== null && !isIdentifier(value.matchId))
  )
    return null
  for (const userId of ready) {
    if (!members.includes(userId)) return null
  }
  for (const userId of connected) {
    if (!members.includes(userId)) return null
  }
  if (value.status === 'WAITING' && value.matchId !== null) return null
  if (value.status !== 'WAITING' && value.matchId === null) return null
  return {
    mode: value.mode,
    roomId: value.roomId,
    ownerUserId: value.ownerUserId,
    memberUserIds: members,
    readyUserIds: ready,
    connectedUserIds: connected,
    version: value.version,
    status: value.status,
    matchId: value.matchId,
  }
}

export function readGameState(value: unknown): GameSnapshot | null {
  if (
    !isObject(value) ||
    (value.mode !== 'PVP' && value.mode !== 'COOP') ||
    !isIdentifier(value.roomId) ||
    !isIdentifier(value.matchId) ||
    !isInteger(value.tick, 0) ||
    !isPositiveNumber(value.serverTime) ||
    (value.status !== 'LOADING' && value.status !== 'PLAYING' && value.status !== 'FINISHED') ||
    !Array.isArray(value.tanks) ||
    value.tanks.length !== 2 ||
    !Array.isArray(value.bullets) ||
    value.bullets.length > 40 ||
    !Array.isArray(value.destroyedCells) ||
    value.destroyedCells.length > 16384 ||
    (value.winnerUserId !== null && !isInteger(value.winnerUserId, 1)) ||
    (value.endReason !== null && typeof value.endReason !== 'string')
  )
    return null
  const tanks: TankSnapshot[] = []
  for (const tank of value.tanks) {
    if (!isObject(tank)) return null
    // 兼容尚未重启的旧后端；缺少保护字段时按无保护显示。
    const shieldUntilTick = tank.shieldUntilTick === undefined ? 0 : tank.shieldUntilTick
    if (
      !isInteger(tank.userId, 1) ||
      typeof tank.x !== 'number' ||
      !Number.isFinite(tank.x) ||
      tank.x < 0 ||
      typeof tank.y !== 'number' ||
      !Number.isFinite(tank.y) ||
      tank.y < 0 ||
      !isPositiveNumber(tank.size) ||
      tank.size > 64 ||
      (tank.direction !== 0 &&
        tank.direction !== 1 &&
        tank.direction !== 2 &&
        tank.direction !== 3) ||
      !isInteger(tank.lastInputSeq, 0) ||
      !isInteger(tank.hp, 0) ||
      tank.hp > 3 ||
      !isInteger(shieldUntilTick, 0)
    )
      return null
    for (const existing of tanks) {
      if (existing.userId === tank.userId) return null
    }
    tanks.push({
      userId: tank.userId,
      x: tank.x,
      y: tank.y,
      size: tank.size,
      direction: tank.direction,
      lastInputSeq: tank.lastInputSeq,
      hp: tank.hp,
      shieldUntilTick,
    })
  }
  if (value.winnerUserId !== null && !tanks.some((tank) => tank.userId === value.winnerUserId))
    return null
  const bullets: BulletSnapshot[] = []
  const enemies: EnemySnapshot[] = []
  if (!Array.isArray(value.enemies) || value.enemies.length > 100) return null
  for (const enemy of value.enemies) {
    if (
      !isObject(enemy) ||
      !isInteger(enemy.id, 1) ||
      enemy.id > 100 ||
      typeof enemy.x !== 'number' ||
      !Number.isFinite(enemy.x) ||
      enemy.x < 0 ||
      typeof enemy.y !== 'number' ||
      !Number.isFinite(enemy.y) ||
      enemy.y < 0 ||
      !isPositiveNumber(enemy.size) ||
      enemy.size > 64 ||
      (enemy.direction !== 0 &&
        enemy.direction !== 1 &&
        enemy.direction !== 2 &&
        enemy.direction !== 3) ||
      !isInteger(enemy.hp, 0) ||
      enemy.hp > 1 ||
      enemy.maxHp !== 1 ||
      enemies.some((existing) => existing.id === enemy.id)
    )
      return null
    enemies.push({
      id: enemy.id,
      x: enemy.x,
      y: enemy.y,
      size: enemy.size,
      direction: enemy.direction,
      hp: enemy.hp,
      maxHp: 1,
    })
  }
  let base: BaseSnapshot | null = null
  if (value.base !== null) {
    const item = value.base
    if (
      !isObject(item) ||
      typeof item.x !== 'number' ||
      !Number.isFinite(item.x) ||
      item.x < 0 ||
      typeof item.y !== 'number' ||
      !Number.isFinite(item.y) ||
      item.y < 0 ||
      !isPositiveNumber(item.size) ||
      item.size > 64 ||
      !isInteger(item.hp, 0) ||
      item.hp > 1 ||
      item.alive !== item.hp > 0
    )
      return null
    base = { x: item.x, y: item.y, size: item.size, hp: item.hp, alive: item.hp > 0 }
  }
  if (
    !isInteger(value.enemyTotal, 0) ||
    !isInteger(value.enemySpawned, 0) ||
    !isInteger(value.enemyDefeated, 0) ||
    value.enemySpawned !== enemies.length ||
    value.enemyDefeated !== enemies.filter((enemy) => enemy.hp === 0).length ||
    value.enemySpawned > value.enemyTotal ||
    enemies.some((enemy) => enemy.id > (value.enemyTotal as number)) ||
    value.enemyTotal > 100 ||
    (value.teamResult !== null &&
      value.teamResult !== 'VICTORY' &&
      value.teamResult !== 'DEFEAT' &&
      value.teamResult !== 'ABANDONED')
  )
    return null
  if (
    value.mode === 'COOP' &&
    (base === null ||
      value.enemyTotal < 1 ||
      value.winnerUserId !== null ||
      (value.status === 'FINISHED') !== (value.teamResult !== null))
  )
    return null
  if (
    value.mode === 'PVP' &&
    (base !== null || enemies.length !== 0 || value.enemyTotal !== 0 || value.teamResult !== null)
  )
    return null
  for (const bullet of value.bullets) {
    if (
      !isObject(bullet) ||
      !isInteger(bullet.id, 1) ||
      typeof bullet.ownerUserId !== 'number' ||
      !Number.isSafeInteger(bullet.ownerUserId) ||
      bullet.ownerUserId === 0 ||
      typeof bullet.x !== 'number' ||
      !Number.isFinite(bullet.x) ||
      bullet.x < 0 ||
      typeof bullet.y !== 'number' ||
      !Number.isFinite(bullet.y) ||
      bullet.y < 0 ||
      (bullet.direction !== 0 &&
        bullet.direction !== 1 &&
        bullet.direction !== 2 &&
        bullet.direction !== 3) ||
      (!tanks.some((tank) => tank.userId === bullet.ownerUserId) &&
        !enemies.some((enemy) => -enemy.id === bullet.ownerUserId)) ||
      bullets.some((existing) => existing.id === bullet.id)
    )
      return null
    bullets.push({
      id: bullet.id,
      ownerUserId: bullet.ownerUserId,
      x: bullet.x,
      y: bullet.y,
      direction: bullet.direction,
    })
  }
  const destroyedCells: number[] = []
  for (const cell of value.destroyedCells) {
    if (!isInteger(cell, 0) || cell >= 16384 || destroyedCells.includes(cell)) return null
    destroyedCells.push(cell)
  }
  return {
    mode: value.mode,
    enemies,
    base,
    enemyTotal: value.enemyTotal,
    enemySpawned: value.enemySpawned,
    enemyDefeated: value.enemyDefeated,
    teamResult: value.teamResult,
    roomId: value.roomId,
    matchId: value.matchId,
    status: value.status,
    tick: value.tick,
    serverTime: value.serverTime,
    tanks,
    bullets,
    destroyedCells,
    winnerUserId: value.winnerUserId,
    endReason: value.endReason,
  }
}

export function readGameInit(value: unknown): GameInitialization | null {
  if (
    !isObject(value) ||
    !isIdentifier(value.roomId) ||
    !isIdentifier(value.matchId) ||
    !isInteger(value.roomVersion, 1)
  )
    return null
  const map = value.map
  const rules = value.rules
  const state = readGameState(value.state)
  if (
    !isObject(map) ||
    !isInteger(map.id, 1) ||
    typeof map.name !== 'string' ||
    !isInteger(map.tileSize, 8) ||
    map.tileSize > 64 ||
    !Array.isArray(map.seed) ||
    map.seed.length < 1 ||
    map.seed.length > 128 ||
    !isObject(rules) ||
    !isPositiveNumber(rules.moveSpeed) ||
    !isInteger(rules.tickMillis, 1) ||
    !isInteger(rules.inputTimeoutMillis, 1) ||
    !isInteger(rules.initialHp, 1) ||
    rules.initialHp > 3 ||
    !isPositiveNumber(rules.bulletSpeed) ||
    !isInteger(rules.fireCooldownMillis, 1) ||
    !isInteger(rules.matchDurationMillis, 1) ||
    state === null ||
    state.roomId !== value.roomId ||
    state.matchId !== value.matchId
  )
    return null
  const seed: number[][] = []
  let columns = 0
  for (const row of map.seed) {
    if (!Array.isArray(row) || row.length < 1 || row.length > 128) return null
    if (columns === 0) columns = row.length
    if (row.length !== columns) return null
    const cells: number[] = []
    for (const cell of row) {
      if (!isInteger(cell, 0) || cell > 5) return null
      cells.push(cell)
    }
    seed.push(cells)
  }
  const width = columns * map.tileSize
  const height = seed.length * map.tileSize
  for (const tank of state.tanks) {
    if (tank.x + tank.size > width + 0.001 || tank.y + tank.size > height + 0.001) return null
  }
  for (const enemy of state.enemies) {
    if (enemy.x + enemy.size > width + 0.001 || enemy.y + enemy.size > height + 0.001) return null
  }
  if (
    state.base &&
    (state.base.x + state.base.size > width || state.base.y + state.base.size > height)
  )
    return null
  for (const cell of state.destroyedCells) {
    if (cell >= columns * seed.length || seed[Math.floor(cell / columns)]?.[cell % columns] !== 1)
      return null
  }
  return {
    roomId: value.roomId,
    matchId: value.matchId,
    roomVersion: value.roomVersion,
    map: { id: map.id, name: map.name, tileSize: map.tileSize, seed },
    rules: {
      moveSpeed: rules.moveSpeed,
      tickMillis: rules.tickMillis,
      inputTimeoutMillis: rules.inputTimeoutMillis,
      initialHp: rules.initialHp,
      bulletSpeed: rules.bulletSpeed,
      fireCooldownMillis: rules.fireCooldownMillis,
      matchDurationMillis: rules.matchDurationMillis,
    },
    state,
  }
}

export function readRoomLeft(value: unknown): RoomDeparture | null {
  if (!isObject(value) || !isIdentifier(value.roomId) || !isInteger(value.version, 1)) return null
  return { roomId: value.roomId, version: value.version }
}

export function readGameEnd(value: unknown): GameEnding | null {
  if (
    !isObject(value) ||
    !isIdentifier(value.roomId) ||
    !isIdentifier(value.matchId) ||
    typeof value.reason !== 'string' ||
    (value.leavingUserId !== null && !isInteger(value.leavingUserId, 1)) ||
    (value.winnerUserId !== null && !isInteger(value.winnerUserId, 1))
  )
    return null
  const state = readGameState(value.state)
  if (state === null || state.roomId !== value.roomId || state.matchId !== value.matchId)
    return null
  return {
    roomId: value.roomId,
    matchId: value.matchId,
    reason: value.reason,
    leavingUserId: value.leavingUserId,
    winnerUserId: value.winnerUserId,
    state,
  }
}
