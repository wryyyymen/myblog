import assert from 'node:assert/strict'
import test from 'node:test'
import { loadGameModule } from './load-game-module.mjs'

const engine = await loadGameModule(new URL('../src/game/singleplayer/engine.ts', import.meta.url))
const {
  createSinglePlayerGame,
  startSinglePlayerGame,
  pauseSinglePlayerGame,
  stepSinglePlayerGame,
} = engine
const idle = { direction: null, fireHeld: false }

function emptyBattlefield() {
  const state = createSinglePlayerGame(7)
  startSinglePlayerGame(state)
  state.map = state.map.map((row, r) =>
    row.map((_, c) => (r === 0 || c === 0 || r === 25 || c === 25 ? 2 : 0)),
  )
  state.nextSpawnAt = Infinity
  state.player.x = 48
  state.player.y = 48
  state.player.shieldUntil = 0
  return state
}

function enemyAt(state, x, y) {
  const enemy = {
    ...state.player,
    id: 2,
    team: 'enemy',
    x,
    y,
    direction: 1,
    hp: 1,
    maxHp: 1,
    nextFireAt: Infinity,
    thinkAt: Infinity,
    path: [],
    stuckFor: 0,
    shieldUntil: 0,
  }
  state.enemies.push(enemy)
  return enemy
}

function bullet(team, x, y, direction = 3, id = 100) {
  return { id, team, ownerId: team === 'player' ? 1 : 2, x, y, direction, radius: 2.5, speed: 320 }
}

function advance(state, frames, input = idle) {
  for (let i = 0; i < frames; i++) stepSinglePlayerGame(state, input)
}

test('开局默认3血、8名敌军和完整基地，敌军分批登场且最多同时3辆', () => {
  const state = createSinglePlayerGame(1)
  assert.equal(state.status, 'ready')
  assert.equal(state.player.hp, 3)
  assert.equal(state.base.alive, true)
  assert.equal(state.totalEnemies, 8)
  assert.equal(state.map.length, 26)
  startSinglePlayerGame(state)
  advance(state, 480)
  assert.equal(state.enemies.length, 3)
  assert.equal(state.spawnedEnemies, 3)
})

test('坦克按速度移动，松开停止且不能穿出边界', () => {
  const state = emptyBattlefield()
  advance(state, 30, { direction: 3, fireHeld: false })
  assert.ok(Math.abs(state.player.x - 104) < 0.0001)
  advance(state, 30)
  assert.ok(Math.abs(state.player.x - 104) < 0.0001)
  advance(state, 150, { direction: 2, fireHeld: false })
  assert.ok(Math.abs(state.player.x - 24) < 0.0001)
})

test('砖、钢、水阻挡移动，树林和冰面可以通行', () => {
  for (const material of [1, 2, 3, 4, 5]) {
    const state = emptyBattlefield()
    state.player.x = 80
    state.player.y = 74.4
    state.map[3][5] = material
    advance(state, 60, { direction: 3, fireHeld: false })
    if (material <= 3) assert.ok(Math.abs(state.player.x + state.player.size - 120) < 0.0001)
    else assert.ok(state.player.x > 144)
  }
})

test('开火遵守冷却及最多3颗在场子弹，松开不继续生成', () => {
  const state = emptyBattlefield()
  state.player.x = 300
  state.player.y = 500
  advance(state, 6, { direction: null, fireHeld: true })
  assert.equal(state.bullets.length, 1)
  advance(state, 15, { direction: null, fireHeld: true })
  assert.equal(state.bullets.length, 2)
  advance(state, 39, { direction: null, fireHeld: true })
  assert.equal(state.bullets.length, 3)
  const previousId = state.nextId
  advance(state, 12)
  assert.equal(state.nextId, previousId)
})

test('子弹打破砖墙、被钢墙吸收、穿过河流', () => {
  for (const material of [1, 2, 3]) {
    const state = emptyBattlefield()
    state.map[4][5] = material
    state.bullets.push(bullet('player', 115, 108))
    advance(state, 1)
    assert.equal(state.map[4][5], material === 1 ? 0 : material)
    assert.equal(state.bullets.length, material === 3 ? 1 : 0)
  }
})

test('玩家受击扣血，短暂无敌期间不重复扣血', () => {
  const state = emptyBattlefield()
  state.bullets.push(bullet('enemy', 72, 57.6, 2))
  advance(state, 1)
  assert.equal(state.player.hp, 2)
  state.bullets.push(bullet('enemy', 72, 57.6, 2))
  advance(state, 1)
  assert.equal(state.player.hp, 2)
})

test('击毁最后一辆敌军获胜，结束后停止继续推进', () => {
  const state = emptyBattlefield()
  state.totalEnemies = 1
  state.spawnedEnemies = 1
  enemyAt(state, 98.4, 98.4)
  state.bullets.push(bullet('player', 94, 108))
  advance(state, 1)
  assert.equal(state.kills, 1)
  assert.equal(state.score, 100)
  assert.equal(state.status, 'won')
  const time = state.time
  advance(state, 60, { direction: 3, fireHeld: true })
  assert.equal(state.time, time)
})

test('玩家装甲归零或敌军击毁基地时失败', () => {
  const playerLost = emptyBattlefield()
  playerLost.player.hp = 1
  playerLost.bullets.push(bullet('enemy', 72, 57.6, 2))
  advance(playerLost, 1)
  assert.equal(playerLost.status, 'lost')
  assert.equal(playerLost.player.hp, 0)
  const baseLost = emptyBattlefield()
  baseLost.bullets.push(bullet('enemy', baseLost.base.x + 12, baseLost.base.y - 5, 1))
  advance(baseLost, 1)
  assert.equal(baseLost.base.alive, false)
  assert.equal(baseLost.status, 'lost')
})

test('AI发现同列玩家会瞄准并开火', () => {
  const state = emptyBattlefield()
  state.player.x = 98.4
  state.player.y = 242.4
  const enemy = enemyAt(state, 98.4, 98.4)
  enemy.nextFireAt = 0
  advance(state, 1)
  assert.equal(enemy.direction, 1)
  assert.ok(state.bullets.some((shot) => shot.team === 'enemy'))
})

test('AI在钢墙转角重新规划时不会一直卡住', () => {
  const state = emptyBattlefield()
  state.map[6][10] = 2
  state.map[4][11] = 2
  const enemy = enemyAt(state, 242.4, 118.4)
  enemy.thinkAt = 0
  advance(state, 600)
  assert.ok(Math.hypot(enemy.x - 242.4, enemy.y - 118.4) > 100)
})

test('暂停冻结战局，再次开始和新建游戏能正确恢复或清空状态', () => {
  const state = emptyBattlefield()
  pauseSinglePlayerGame(state)
  const x = state.player.x
  advance(state, 60, { direction: 3, fireHeld: true })
  assert.equal(state.player.x, x)
  assert.equal(state.time, 0)
  pauseSinglePlayerGame(state)
  advance(state, 1, { direction: 3, fireHeld: false })
  assert.ok(state.player.x > x)
  state.map[23][13] = 0
  state.player.hp = 1
  const fresh = createSinglePlayerGame(7)
  assert.equal(fresh.player.hp, 3)
  assert.equal(fresh.map[23][13], 1)
  assert.equal(fresh.kills, 0)
  assert.equal(fresh.bullets.length, 0)
})

test('正常地图上的敌军会持续进攻，玩家不防守最终会失败', () => {
  const state = createSinglePlayerGame(9)
  startSinglePlayerGame(state)
  advance(state, 180 * 60)
  assert.equal(state.status, 'lost')
  assert.ok(state.spawnedEnemies >= 2)
  assert.ok(!state.base.alive || !state.player.alive)
})
