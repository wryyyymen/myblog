import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { loadGameModule } from './load-game-module.mjs'

const { gameLevels, gameMapSettings, getGameLevel, createLevelMap } = await loadGameModule(
  new URL('../src/game/maps/catalog.ts', import.meta.url),
)
const { createSinglePlayerGame, startSinglePlayerGame, stepSinglePlayerGame } =
  await loadGameModule(new URL('../src/game/singleplayer/engine.ts', import.meta.url))
const shared = JSON.parse(
  await readFile(
    new URL('../../backend/src/main/resources/game/maps/levels.json', import.meta.url),
    'utf8',
  ),
)
const idle = { direction: null, fireHeld: false }

function reachableCells(map, start, base) {
  const key = (cell) => cell.row * 26 + cell.column
  const visited = new Set([key(start)])
  const queue = [start]
  for (let index = 0; index < queue.length; index++) {
    const cell = queue[index]
    for (const [rowDelta, columnDelta] of [
      [-1, 0],
      [1, 0],
      [0, -1],
      [0, 1],
    ]) {
      const next = { row: cell.row + rowDelta, column: cell.column + columnDelta }
      if (next.row < 1 || next.row > 24 || next.column < 1 || next.column > 24) continue
      if (next.row === base.row && next.column === base.column) continue
      if (![0, 4, 5].includes(map[next.row][next.column]) || visited.has(key(next))) continue
      visited.add(key(next))
      queue.push(next)
    }
  }
  return visited
}

test('五关目录直接读取唯一JSON配置，难度与敌军总数递增', () => {
  assert.equal(gameLevels.length, 5)
  assert.deepEqual(
    gameLevels.map((level) => level.id),
    [1, 2, 3, 4, 5],
  )
  assert.deepEqual(
    gameLevels.map((level) => level.enemyTotal),
    [8, 10, 12, 14, 16],
  )
  assert.equal(new Set(gameLevels.map((level) => level.rows.join(''))).size, 5)
  for (let index = 0; index < gameLevels.length; index++) {
    assert.deepEqual(gameLevels[index], shared.levels[index])
    if (index === 0) continue
    assert.ok(gameLevels[index].enemySpeed > gameLevels[index - 1].enemySpeed)
    assert.ok(gameLevels[index].spawnIntervalMillis < gameLevels[index - 1].spawnIntervalMillis)
  }
})

test('所有关卡的出生点、基地保护墙和行走路线有效，无孤立可走区域', () => {
  for (const level of gameLevels) {
    const map = createLevelMap(level.id)
    assert.equal(map.length, 26)
    const reachable = reachableCells(map, gameMapSettings.playerSpawns[0], gameMapSettings.base)
    for (let row = 0; row < 26; row++) {
      assert.equal(map[row].length, 26)
      for (let column = 0; column < 26; column++) {
        const material = map[row][column]
        assert.ok(Number.isInteger(material) && material >= 0 && material <= 5)
        if (row === 0 || row === 25 || column === 0 || column === 25) assert.equal(material, 2)
        if (row === gameMapSettings.base.row && column === gameMapSettings.base.column) continue
        if ([0, 4, 5].includes(material))
          assert.ok(reachable.has(row * 26 + column), `第${level.id}关格子(${row},${column})不可达`)
      }
    }
    for (const spawn of [...gameMapSettings.playerSpawns, ...gameMapSettings.enemySpawns]) {
      assert.equal(map[spawn.row][spawn.column], 0)
      assert.ok(reachable.has(spawn.row * 26 + spawn.column))
    }
    assert.equal(map[24][13], 0)
    for (const [row, column] of [
      [23, 12],
      [23, 13],
      [23, 14],
      [24, 12],
      [24, 14],
    ])
      assert.equal(map[row][column], 1, `第${level.id}关基地保护墙缺失`)
  }
})

test('只读目录不会被战斗地图破坏污染，重开与跨关地图独立', () => {
  for (const level of gameLevels) {
    const first = createLevelMap(level.id)
    const second = createLevelMap(level.id)
    first[23][13] = 0
    assert.equal(second[23][13], 1)
    assert.equal(createLevelMap(level.id)[23][13], 1)
    assert.equal(level.rows[23][13], '1')
    assert.ok(Object.isFrozen(level) && Object.isFrozen(level.rows))
    assert.throws(() => {
      level.rows[23] = '0'.repeat(26)
    }, TypeError)
  }
  assert.ok(Object.isFrozen(gameLevels))
  assert.throws(() => getGameLevel(0), RangeError)
  assert.throws(() => getGameLevel(6), RangeError)
  assert.throws(() => createSinglePlayerGame(1, 99), RangeError)
})

test('单机使用所选关卡的敌军总数、出生位置、间隔、上限和移动速度', () => {
  for (const level of gameLevels) {
    const state = createSinglePlayerGame(7, level.id)
    assert.equal(state.levelId, level.id)
    assert.equal(state.levelName, level.name)
    assert.equal(state.totalEnemies, level.enemyTotal)
    assert.equal(state.maxAliveEnemies, level.maxAliveEnemies)
    assert.equal(state.spawnIntervalSeconds, level.spawnIntervalMillis / 1000)
    startSinglePlayerGame(state)
    stepSinglePlayerGame(state, idle)
    assert.equal(state.spawnedEnemies, 1)
    const enemy = state.enemies[0]
    const spawn = gameMapSettings.enemySpawns[0]
    assert.equal(Math.floor((enemy.x + enemy.size / 2) / 24), spawn.column)
    assert.equal(Math.floor((enemy.y + enemy.size / 2) / 24), spawn.row)
    assert.equal(state.nextSpawnAt - state.time, level.spawnIntervalMillis / 1000)

    // 清空地形并指定一条无障碍路径，只测关卡速度，避免被AI选路差异影响。
    state.map = state.map.map((row, r) =>
      row.map((_, c) => (r === 0 || r === 25 || c === 0 || c === 25 ? 2 : 0)),
    )
    state.nextSpawnAt = Infinity
    enemy.x = 50.4
    enemy.y = 50.4
    enemy.path = [{ row: 2, column: 3 }]
    enemy.thinkAt = Infinity
    enemy.nextFireAt = Infinity
    const before = enemy.x
    stepSinglePlayerGame(state, idle)
    assert.ok(Math.abs(enemy.x - before - level.enemySpeed / 60) < 0.00001)

    // 达到本关上限时，即使出兵时间已经到也不额外刷怪。
    while (state.enemies.length < level.maxAliveEnemies)
      state.enemies.push({ ...enemy, id: state.enemies.length + 10, path: [] })
    state.nextSpawnAt = 0
    const spawned = state.spawnedEnemies
    stepSinglePlayerGame(state, idle)
    assert.equal(state.spawnedEnemies, spawned)
  }
})

test('五关分别分批生成完整敌军，击毁最后一辆才胜利并停止出兵', () => {
  for (const level of gameLevels) {
    const state = createSinglePlayerGame(21, level.id)
    startSinglePlayerGame(state)
    for (let frame = 0; frame < 4000 && state.status === 'playing'; frame++) {
      stepSinglePlayerGame(state, idle)
      assert.ok(state.enemies.length <= level.maxAliveEnemies)
      assert.ok(state.spawnedEnemies <= level.enemyTotal)
      for (const enemy of state.enemies) {
        enemy.nextFireAt = Infinity
        state.bullets.push({
          id: state.nextId++,
          team: 'player',
          ownerId: state.player.id,
          x: enemy.x + enemy.size / 2,
          y: enemy.y + enemy.size / 2,
          direction: 0,
          radius: 2.5,
          speed: 320,
        })
      }
      if (state.kills < level.enemyTotal) assert.notEqual(state.status, 'won')
    }
    assert.equal(state.spawnedEnemies, level.enemyTotal, `第${level.id}关出兵总数`)
    assert.equal(state.kills, level.enemyTotal)
    assert.equal(state.score, level.enemyTotal * 100)
    assert.equal(state.status, 'won')
    assert.equal(state.base.alive, true)
    const time = state.time
    stepSinglePlayerGame(state, { direction: 3, fireHeld: true })
    assert.equal(state.time, time)
    assert.equal(state.enemies.length, 0)
  }
})
