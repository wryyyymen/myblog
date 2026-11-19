import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(
  new URL('../src/game/network/interpolation.ts', import.meta.url),
  'utf8',
)
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const { interpolatePosition, interpolationDuration } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
)

test('100ms 子弹快照之间能补出每个屏幕帧的位置', () => {
  const coordinates = []
  for (let frame = 0; frame <= 6; frame++) {
    const position = interpolatePosition({ x: 48, y: 120 }, { x: 48, y: 84 }, 0, frame / 6)
    coordinates.push(position.y)
    assert.equal(position.x, 48)
  }
  assert.deepEqual(coordinates, [120, 114, 108, 102, 96, 90, 84])
})

test('网络停顿后停在服务端确认的位置，不越过终点', () => {
  assert.deepEqual(interpolatePosition({ x: 0, y: 24 }, { x: 12, y: 24 }, 3, 5), { x: 12, y: 24 })
  assert.deepEqual(interpolatePosition({ x: 0, y: 24 }, { x: 12, y: 24 }, 3, -1), { x: 0, y: 24 })
})

test('从横向转到纵向时沿拐角补帧，避免跳帧和斜切', () => {
  const from = { x: 24, y: 24 }
  const to = { x: 36, y: 36 }
  assert.deepEqual(interpolatePosition(from, to, 1, 0.25), { x: 30, y: 24 })
  assert.deepEqual(interpolatePosition(from, to, 1, 0.5), { x: 36, y: 24 })
  assert.deepEqual(interpolatePosition(from, to, 1, 0.75), { x: 36, y: 30 })
})

test('从纵向转到横向时先完成纵向，再向左移动', () => {
  const from = { x: 36, y: 36 }
  const to = { x: 24, y: 24 }
  assert.deepEqual(interpolatePosition(from, to, 2, 0.25), { x: 36, y: 30 })
  assert.deepEqual(interpolatePosition(from, to, 2, 0.75), { x: 30, y: 24 })
})

test('下一份快照从当前显示位置接续，不倒退回旧坐标', () => {
  const shown = interpolatePosition({ x: 0, y: 24 }, { x: 12, y: 24 }, 3, 0.75)
  assert.deepEqual(interpolatePosition(shown, { x: 24, y: 24 }, 3, 0), shown)
  assert.equal(interpolatePosition(shown, { x: 24, y: 24 }, 3, 0.5).x, 16.5)
})

test('补帧时长使用模拟 tick，限制异常长间隔', () => {
  assert.equal(interpolationDuration(2, 4, 50), 100)
  assert.equal(interpolationDuration(2, 3, 50), 50)
  assert.equal(interpolationDuration(2, 20, 50), 200)
})
