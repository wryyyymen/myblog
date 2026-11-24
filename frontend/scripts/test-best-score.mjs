import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/game/results.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const {
  isBetterScore,
  loadBestScores,
  readBestScore,
  readBestScores,
  readGameResult,
  saveBestScores,
  updateBestScore,
} = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

/** 每个用例都用一份干净的假 localStorage，避免互相影响。 */
function useMemoryStorage() {
  const values = new Map()
  globalThis.localStorage = {
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
  return values
}

function victory(overrides = {}) {
  return {
    mode: 'single',
    levelId: 1,
    levelName: '基地防线',
    matchId: 'match-1',
    outcome: 'VICTORY',
    reason: '全部敌军已消灭',
    score: 800,
    defeated: 8,
    enemyTotal: 8,
    elapsedSeconds: 60,
    remainingHp: 2,
    maxHp: 3,
    baseAlive: true,
    bestScore: 800,
    isNewRecord: true,
    ...overrides,
  }
}

test('first victory on a level becomes the record', () => {
  useMemoryStorage()
  const record = updateBestScore(victory())
  assert.equal(record.isNewRecord, true)
  assert.equal(record.best.score, 800)
  assert.equal(loadBestScores()[1].score, 800)
})

test('a higher score replaces the old record', () => {
  useMemoryStorage()
  updateBestScore(victory({ score: 800 }))
  const record = updateBestScore(victory({ score: 1100, matchId: 'match-2' }))
  assert.equal(record.isNewRecord, true)
  assert.equal(record.best.score, 1100)
  assert.equal(loadBestScores()[1].score, 1100)
})

test('a lower score keeps the old record and reports no record', () => {
  useMemoryStorage()
  updateBestScore(victory({ score: 1100 }))
  const record = updateBestScore(victory({ score: 500, matchId: 'match-2' }))
  assert.equal(record.isNewRecord, false)
  assert.equal(record.best.score, 1100)
  assert.equal(loadBestScores()[1].score, 1100)
})

test('same score with a shorter time wins, same score and time does not', () => {
  useMemoryStorage()
  updateBestScore(victory({ score: 800, elapsedSeconds: 60 }))

  const faster = updateBestScore(victory({ score: 800, elapsedSeconds: 41, matchId: 'match-2' }))
  assert.equal(faster.isNewRecord, true)
  assert.equal(faster.best.elapsedSeconds, 41)

  const same = updateBestScore(victory({ score: 800, elapsedSeconds: 41, matchId: 'match-3' }))
  assert.equal(same.isNewRecord, false)
})

test('defeat, co-op and missing level never enter the record table', () => {
  useMemoryStorage()
  assert.equal(updateBestScore(victory({ outcome: 'DEFEAT' })), null)
  assert.equal(updateBestScore(victory({ mode: 'coop' })), null)
  assert.equal(updateBestScore(victory({ levelId: undefined })), null)
  assert.equal(updateBestScore(victory({ score: null })), null)
  assert.deepEqual(loadBestScores(), {})
})

test('each level keeps its own record', () => {
  useMemoryStorage()
  updateBestScore(victory({ levelId: 1, score: 800 }))
  updateBestScore(victory({ levelId: 5, levelName: '冰原要塞', score: 1600 }))
  const scores = loadBestScores()
  assert.equal(scores[1].score, 800)
  assert.equal(scores[5].score, 1600)
})

test('a corrupted entry is dropped without losing the other levels', () => {
  useMemoryStorage()
  updateBestScore(victory({ levelId: 1, score: 800 }))
  updateBestScore(victory({ levelId: 2, levelName: '河谷三桥', score: 1000 }))

  const stored = JSON.parse(globalThis.localStorage.getItem('tank:best-scores'))
  stored['2'] = { levelId: 2, score: 'not-a-number' }
  globalThis.localStorage.setItem('tank:best-scores', JSON.stringify(stored))

  const scores = loadBestScores()
  assert.equal(scores[1].score, 800)
  assert.equal(scores[2], undefined)
})

test('invalid single records are rejected field by field', () => {
  const valid = {
    levelId: 3,
    score: 1200,
    defeated: 12,
    elapsedSeconds: 88,
    achievedAt: '2026-11-24T12:00:00.000Z',
  }
  assert.ok(readBestScore(valid))
  for (const changes of [
    { levelId: 0 },
    { levelId: 6 },
    { score: -1 },
    { score: NaN },
    { defeated: 10_001 },
    { elapsedSeconds: 86_401 },
    { achievedAt: '' },
    { achievedAt: 123 },
  ])
    assert.equal(readBestScore({ ...valid, ...changes }), null)
})

test('the whole table tolerates non-objects and array payloads', () => {
  assert.deepEqual(readBestScores(null), {})
  assert.deepEqual(readBestScores('[]'), {})
  assert.deepEqual(readBestScores([]), {})
})

test('blocked localStorage never breaks the result page', () => {
  globalThis.localStorage = {
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
  assert.deepEqual(loadBestScores(), {})
  const record = updateBestScore(victory())
  assert.equal(record.isNewRecord, true)
  assert.doesNotThrow(() => saveBestScores({}))
})

test('isBetterScore handles an empty table and ties', () => {
  assert.equal(isBetterScore({ score: 1, elapsedSeconds: 10 }, undefined), true)
  assert.equal(isBetterScore({ score: 1, elapsedSeconds: 10 }, { score: 1, elapsedSeconds: 10 }), false)
  assert.equal(isBetterScore({ score: 2, elapsedSeconds: 99 }, { score: 1, elapsedSeconds: 10 }), true)
})

test('stored result accepts best score fields but rejects malformed ones', () => {
  const valid = victory()
  assert.equal(readGameResult(valid).bestScore, 800)
  assert.equal(readGameResult(valid).isNewRecord, true)
  assert.equal(readGameResult({ ...valid, bestScore: -1 }), null)
  assert.equal(readGameResult({ ...valid, isNewRecord: 'yes' }), null)

  const withoutBest = victory()
  delete withoutBest.bestScore
  delete withoutBest.isNewRecord
  const checked = readGameResult(withoutBest)
  assert.equal('bestScore' in checked, false)
  assert.equal('isNewRecord' in checked, false)
})
