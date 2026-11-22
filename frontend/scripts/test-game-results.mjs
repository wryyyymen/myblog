import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/game/results.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const {
  createSinglePlayerResult,
  readGameResult,
  saveGameResult,
  loadGameResult,
  clearGameResult,
} = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

function finishedState(status = 'won') {
  return {
    status,
    score: 800,
    kills: 8,
    totalEnemies: 8,
    time: 72.6,
    player: { hp: 2, maxHp: 3 },
    base: { alive: true },
    result: '基地已守住。',
  }
}

test('single-player ending preserves score, health and duration for a separate result page', () => {
  const result = createSinglePlayerResult(finishedState(), 'match-1')
  assert.equal(result.outcome, 'VICTORY')
  assert.equal(result.score, 800)
  assert.equal(result.defeated, 8)
  assert.equal(result.elapsedSeconds, 72)
  assert.equal(result.remainingHp, 2)
  assert.ok(readGameResult(result))
  assert.equal(createSinglePlayerResult(finishedState('lost'), 'match-2').outcome, 'DEFEAT')
  assert.equal(createSinglePlayerResult(finishedState('playing'), 'match-3'), null)
})

test('invalid stored outcomes, numbers and health cannot become a result', () => {
  const valid = createSinglePlayerResult(finishedState(), 'match-1')
  for (const changes of [
    { outcome: 'UNKNOWN' },
    { remainingHp: -1 },
    { remainingHp: 9 },
    { score: NaN },
    { elapsedSeconds: -1 },
    { defeated: 9 },
    { matchId: '' },
  ])
    assert.equal(readGameResult({ ...valid, ...changes }), null)
  assert.equal(readGameResult(null), null)
})

test('results preserve the selected level across reload and reject corrupt level metadata', () => {
  const state = {
    ...finishedState(),
    levelId: 4,
    levelName: '密林伏击',
    totalEnemies: 14,
    kills: 14,
  }
  const result = createSinglePlayerResult(state, 'level-4')
  assert.equal(result.levelId, 4)
  assert.equal(result.levelName, '密林伏击')
  assert.deepEqual(readGameResult(result), result)
  for (const invalid of [{ levelId: 0 }, { levelId: 6 }, { levelName: '' }, { levelName: null }])
    assert.equal(readGameResult({ ...result, ...invalid }), null)
})

test('only the public result fields are copied from a stored record', () => {
  const valid = createSinglePlayerResult(finishedState(), 'match-1')
  const checked = readGameResult({ ...valid, token: 'not-a-real-token', controls: {} })
  assert.deepEqual(checked, valid)
  assert.equal('token' in checked, false)
})

test('session result survives refresh and can be cleared before a fresh match', () => {
  const values = new Map()
  globalThis.sessionStorage = {
    setItem(key, value) {
      values.set(key, value)
    },
    getItem(key) {
      return values.get(key) ?? null
    },
    removeItem(key) {
      values.delete(key)
    },
  }
  const result = createSinglePlayerResult(finishedState(), 'match-1')
  saveGameResult('test:result', result)
  assert.deepEqual(loadGameResult('test:result'), result)
  const loaded = loadGameResult('test:result')
  loaded.score = 0
  assert.equal(loadGameResult('test:result').score, 800)
  clearGameResult('test:result')
  assert.equal(loadGameResult('test:result'), null)
})

test('corrupt or unavailable browser storage does not break the result page', () => {
  globalThis.sessionStorage = {
    getItem() {
      return '{invalid'
    },
  }
  assert.equal(loadGameResult('test:result'), null)
  globalThis.sessionStorage = {
    getItem() {
      throw new Error('storage blocked')
    },
    setItem() {
      throw new Error('storage blocked')
    },
    removeItem() {
      throw new Error('storage blocked')
    },
  }
  assert.equal(loadGameResult('test:result'), null)
  assert.doesNotThrow(() =>
    saveGameResult('test:result', createSinglePlayerResult(finishedState(), 'match-1')),
  )
  assert.doesNotThrow(() => clearGameResult('test:result'))
})
