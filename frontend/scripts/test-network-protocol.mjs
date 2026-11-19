import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/game/network/protocol.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const { readRoom, readGameState, readGameInit, readGameEnd, readAvailableRooms } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
)

test('available room summaries accept empty and joinable lists without member details', () => {
  assert.deepEqual(readAvailableRooms([]), [])
  const room = {
    roomId: 'room',
    ownerUserId: 14,
    memberCount: 1,
    maxMembers: 2,
    mode: 'COOP',
    version: 1,
  }
  assert.deepEqual(readAvailableRooms([room]), [room])
})

test('available room summaries reject full rooms, duplicate identifiers and invalid modes', () => {
  const room = {
    roomId: 'room',
    ownerUserId: 14,
    memberCount: 1,
    maxMembers: 2,
    mode: 'COOP',
    version: 1,
  }
  assert.equal(readAvailableRooms([{ ...room, memberCount: 2 }]), null)
  assert.equal(readAvailableRooms([{ ...room, mode: 'unknown' }]), null)
  assert.equal(readAvailableRooms([room, { ...room }]), null)
})

function state() {
  return {
    roomId: 'room',
    matchId: 'match',
    mode: 'COOP',
    status: 'PLAYING',
    tick: 10,
    serverTime: 1000,
    tanks: [14, 15].map((userId, index) => ({
      userId,
      x: 48 + index * 24,
      y: 72,
      size: 19.2,
      direction: 0,
      lastInputSeq: 1,
      hp: 3,
      shieldUntilTick: 0,
    })),
    enemies: [{ id: 1, x: 48, y: 24, size: 19.2, direction: 1, hp: 1, maxHp: 1 }],
    base: { x: 96, y: 120, size: 24, hp: 1, alive: true },
    enemyTotal: 8,
    enemySpawned: 1,
    enemyDefeated: 0,
    bullets: [{ id: 1, ownerUserId: -1, x: 58, y: 50, direction: 1 }],
    destroyedCells: [],
    winnerUserId: null,
    endReason: null,
    teamResult: null,
  }
}

test('cooperative snapshots accept NPC bullets, a shared base and two human tanks', () => {
  const snapshot = readGameState(state())
  assert.ok(snapshot)
  assert.equal(snapshot.bullets[0].ownerUserId, -1)
  assert.equal(snapshot.base.alive, true)
  assert.equal(snapshot.tanks.length, 2)
})

test('protection ticks are preserved, invalid values rejected and older snapshots remain readable', () => {
  const value = state()
  value.tanks[0].shieldUntilTick = 60
  assert.equal(readGameState(value).tanks[0].shieldUntilTick, 60)
  value.tanks[0].shieldUntilTick = -1
  assert.equal(readGameState(value), null)
  delete value.tanks[0].shieldUntilTick
  assert.equal(readGameState(value).tanks[0].shieldUntilTick, 0)
})

test('invalid AI identities, inconsistent waves and forged bullet owners are rejected', () => {
  const invalid = [
    (value) => {
      value.bullets[0].ownerUserId = -8
    },
    (value) => {
      value.enemies[0].id = -1
    },
    (value) => {
      value.enemySpawned = 8
    },
    (value) => {
      value.enemyDefeated = 1
    },
    (value) => {
      value.enemies[0].x = NaN
    },
    (value) => {
      value.base.alive = false
    },
    (value) => {
      value.winnerUserId = 14
    },
  ]
  for (const corrupt of invalid) {
    const value = state()
    corrupt(value)
    assert.equal(readGameState(value), null)
  }
})

test('cooperative ending requires a shared result and preserves it for both users', () => {
  const value = state()
  value.status = 'FINISHED'
  value.endReason = 'BASE_DESTROYED'
  value.base.hp = 0
  value.base.alive = false
  assert.equal(readGameState(value), null)
  value.teamResult = 'DEFEAT'
  const end = readGameEnd({
    roomId: 'room',
    matchId: 'match',
    state: value,
    winnerUserId: null,
    leavingUserId: null,
    reason: 'BASE_DESTROYED',
  })
  assert.equal(end.state.teamResult, 'DEFEAT')
  assert.equal(end.winnerUserId, null)
})

test('room mode and connected members must be valid and present', () => {
  const room = {
    roomId: 'room',
    mode: 'COOP',
    ownerUserId: 14,
    memberUserIds: [14, 15],
    readyUserIds: [],
    connectedUserIds: [14, 15],
    version: 1,
    status: 'WAITING',
    matchId: null,
  }
  assert.equal(readRoom(room).mode, 'COOP')
  assert.equal(readRoom({ ...room, connectedUserIds: [99] }), null)
  assert.equal(readRoom({ ...room, mode: 'unknown' }), null)
})

test('initialization validates enemies and base against the map boundary', () => {
  const value = {
    roomId: 'room',
    matchId: 'match',
    roomVersion: 1,
    map: {
      id: 1,
      name: 'test',
      tileSize: 24,
      seed: Array.from({ length: 8 }, () => Array(8).fill(0)),
    },
    rules: {
      moveSpeed: 120,
      tickMillis: 50,
      inputTimeoutMillis: 500,
      initialHp: 3,
      bulletSpeed: 360,
      fireCooldownMillis: 350,
      matchDurationMillis: 180000,
    },
    state: state(),
  }
  assert.ok(readGameInit(value))
  value.state.enemies[0].x = 190
  assert.equal(readGameInit(value), null)
  value.state.enemies[0].x = 48
  value.state.base.y = 190
  assert.equal(readGameInit(value), null)
})

test('legacy PVP still accepts a snapshot without cooperative entities', () => {
  const value = state()
  Object.assign(value, {
    mode: 'PVP',
    enemies: [],
    base: null,
    enemyTotal: 0,
    enemySpawned: 0,
    enemyDefeated: 0,
    bullets: [],
  })
  assert.ok(readGameState(value))
})
