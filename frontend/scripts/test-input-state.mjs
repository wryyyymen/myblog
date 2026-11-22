import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/game/inputState.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const { GameInputState } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
)

test('触屏方向释放后恢复仍按住的键盘方向', () => {
  const input = new GameInputState()
  input.setDirection('keyboard', 3)
  input.setDirection('touch', 0)
  assert.equal(input.read().direction, 0)
  input.setDirection('touch', null)
  assert.equal(input.read().direction, 3)
})

test('释放一种开火来源不会停止另一种仍按住的来源', () => {
  const input = new GameInputState()
  input.setFire('keyboard', true)
  input.setFire('touch', true)
  input.setFire('keyboard', false)
  assert.equal(input.read().fireHeld, true)
  input.setFire('keyboard', true)
  input.setFire('touch', false)
  assert.equal(input.read().fireHeld, true)
  input.setFire('keyboard', false)
  assert.equal(input.read().fireHeld, false)
})

test('画布失焦只清键盘，保留触屏移动和射击', () => {
  const input = new GameInputState()
  input.setDirection('keyboard', 1)
  input.setDirection('touch', 2)
  input.setFire('touch', true)
  input.clear('keyboard')
  assert.deepEqual(input.read(), { direction: 2, fireHeld: true })
})

test('新对局清空全部来源，读取结果不能反向修改内部状态', () => {
  const input = new GameInputState()
  input.setDirection('touch', 2)
  input.setFire('keyboard', true)
  const snapshot = input.read()
  snapshot.direction = 0
  assert.equal(input.read().direction, 2)
  input.clearAll()
  assert.deepEqual(input.read(), { direction: null, fireHeld: false })
  input.setFire('touch', true)
  assert.deepEqual(input.read(), { direction: null, fireHeld: true })
})
