import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

async function importTypeScript(path) {
  const source = await readFile(new URL(path, import.meta.url), 'utf8')
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  return import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
}

const { captureSingleAudioFrame, singleAudioEvents, onlineAudioEvents } = await importTypeScript(
  '../src/game/audio/audioEvents.ts',
)
const { createSinglePlayerGame, startSinglePlayerGame } = await importTypeScript(
  '../src/game/singleplayer/engine.ts',
)

function single() {
  const state = createSinglePlayerGame(7)
  startSinglePlayerGame(state)
  return state
}

function online() {
  return {
    roomId: 'room',
    matchId: 'match',
    mode: 'COOP',
    status: 'PLAYING',
    tick: 2,
    serverTime: 1000,
    tanks: [
      { userId: 14, hp: 3 },
      { userId: 15, hp: 3 },
    ],
    enemies: [],
    bullets: [],
    destroyedCells: [],
    enemyDefeated: 0,
    enemyTotal: 8,
    enemySpawned: 0,
    base: { alive: true },
    teamResult: null,
    winnerUserId: null,
    endReason: null,
  }
}

test('单机首次开局播放一次，普通绘制帧不重复发声', () => {
  const frame = captureSingleAudioFrame(single())
  assert.deepEqual(singleAudioEvents(frame), ['start'])
  assert.deepEqual(singleAudioEvents(frame, frame), [])
})

test('轻量快照不会被单机原地修改污染，只在真正开火后播放', () => {
  const state = single()
  const before = captureSingleAudioFrame(state)
  state.player.nextFireAt = 0.3
  const after = captureSingleAudioFrame(state)
  assert.equal(before.fireTimes.get(state.player.id), 0)
  assert.deepEqual(singleAudioEvents(after, before), ['fire'])
  assert.deepEqual(singleAudioEvents(after, after), [])
})

test('单机保护期间血量不变则不播放受击音，真实扣血才播放', () => {
  const state = single()
  const before = captureSingleAudioFrame(state)
  state.player.shieldUntil = 4
  assert.deepEqual(singleAudioEvents(captureSingleAudioFrame(state), before), [])
  state.player.hp = 2
  assert.deepEqual(singleAudioEvents(captureSingleAudioFrame(state), before), ['hit'])
})

test('同一砖块特效只发声一次，击毁敌军与胜负使用对应音效', () => {
  const state = single()
  const before = captureSingleAudioFrame(state)
  state.effects.push({ x: 60, y: 60, radius: 12, expiresAt: 0.35 })
  const brick = captureSingleAudioFrame(state)
  assert.deepEqual(singleAudioEvents(brick, before), ['brick'])
  assert.deepEqual(singleAudioEvents(brick, brick), [])
  state.kills = 1
  assert.deepEqual(singleAudioEvents(captureSingleAudioFrame(state), brick), ['destroy'])
  state.status = 'won'
  const end = captureSingleAudioFrame(state)
  assert.deepEqual(singleAudioEvents(end, brick), ['victory'])
  assert.deepEqual(singleAudioEvents(end, end), [])
})

test('暂停和继续同一局不重复开场，失败只播放一次结算音', () => {
  const state = single()
  const playing = captureSingleAudioFrame(state)
  state.status = 'paused'
  const paused = captureSingleAudioFrame(state)
  assert.deepEqual(singleAudioEvents(paused, playing), [])
  state.status = 'playing'
  assert.deepEqual(singleAudioEvents(captureSingleAudioFrame(state), paused), [])
  state.status = 'lost'
  const end = captureSingleAudioFrame(state)
  assert.deepEqual(singleAudioEvents(end, playing), ['defeat'])
  assert.deepEqual(singleAudioEvents(end, end), [])
})

test('联机加载完成播放开场，重复状态和相同子弹不重复发声', () => {
  const state = online()
  assert.deepEqual(onlineAudioEvents(state, undefined, 14), ['start'])
  const loading = { ...state, status: 'LOADING', tick: 0 }
  assert.deepEqual(onlineAudioEvents(state, loading, 14), ['start'])
  assert.deepEqual(onlineAudioEvents(state, state, 14), [])
  const fire = { ...state, tick: 4, bullets: [{ id: 1, ownerUserId: 14 }] }
  assert.deepEqual(onlineAudioEvents(fire, state, 14), ['fire'])
  assert.deepEqual(onlineAudioEvents({ ...fire, tick: 6 }, fire, 14), [])
})

test('联机新伤害、砖墙和击毁事件各自播放，保护吸收不播放受击', () => {
  const before = online()
  const after = {
    ...before,
    tick: 4,
    destroyedCells: [25],
    enemyDefeated: 1,
    tanks: [
      { userId: 14, hp: 2 },
      { userId: 15, hp: 3 },
    ],
  }
  assert.deepEqual(onlineAudioEvents(after, before, 14), ['hit', 'destroy', 'brick'])
  assert.deepEqual(onlineAudioEvents({ ...before, tick: 4 }, before, 14), [])
})

test('联机初次恢复历史对局和长间隔补同步不补放枪声', () => {
  const before = online()
  const recovered = {
    ...before,
    tick: 200,
    bullets: [{ id: 5 }],
    enemyDefeated: 2,
    destroyedCells: [25],
  }
  assert.deepEqual(onlineAudioEvents(recovered, undefined, 14), [])
  assert.deepEqual(onlineAudioEvents(recovered, before, 14), [])
  assert.deepEqual(onlineAudioEvents(before, recovered, 14), [])
})

test('联机首次恢复结束快照和重复结束消息不重播结算', () => {
  const before = online()
  const victory = { ...before, tick: 100, status: 'FINISHED', teamResult: 'VICTORY' }
  assert.deepEqual(onlineAudioEvents(victory, undefined, 14), [])
  assert.deepEqual(onlineAudioEvents(victory, before, 14), ['victory'])
  assert.deepEqual(onlineAudioEvents(victory, victory, 14), [])
  const defeat = { ...victory, teamResult: 'DEFEAT' }
  assert.deepEqual(onlineAudioEvents(defeat, before, 14), ['defeat'])
})

test('PVP结算按可信快照中的用户身份选择音效，平局不冒充胜利', () => {
  const before = { ...online(), mode: 'PVP' }
  const end = { ...before, status: 'FINISHED', winnerUserId: 14 }
  assert.deepEqual(onlineAudioEvents(end, before, 14), ['victory'])
  assert.deepEqual(onlineAudioEvents(end, before, 15), ['defeat'])
  assert.deepEqual(onlineAudioEvents({ ...end, winnerUserId: null }, before, 14), [])
})
