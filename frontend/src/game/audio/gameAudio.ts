import { readonly, ref } from 'vue'
import type { GameSnapshot } from '../network/protocol'
import type { SinglePlayerState } from '../singleplayer/engine'
import {
  captureSingleAudioFrame,
  singleAudioEvents,
  onlineAudioEvents,
  type GameSound,
  type SingleAudioFrame,
} from './audioEvents'

export type { GameSound } from './audioEvents'

const SETTINGS_KEY = 'tank:audio-settings'
const MAX_VOICES = 8
const UNLOCK_TIMEOUT_MILLIS = 1000
const baseUrl = `${import.meta.env.BASE_URL}resources/audio/tank/`
const definitions: Record<GameSound, { file: string; gain: number; cooldown: number }> = {
  start: { file: 'start.wav', gain: 0.55, cooldown: 800 },
  fire: { file: 'Gunfire.wav', gain: 0.5, cooldown: 45 },
  hit: { file: 'hit.wav', gain: 0.8, cooldown: 90 },
  brick: { file: 'fire.wav', gain: 0.35, cooldown: 80 },
  destroy: { file: 'bang.wav', gain: 0.7, cooldown: 100 },
  victory: { file: 'add.wav', gain: 0.7, cooldown: 1000 },
  defeat: { file: 'blast.wav', gain: 0.7, cooldown: 1000 },
}

const muted = ref(false)
const volume = ref(0.5)
const unlocked = ref(false)
export const gameAudioMuted = readonly(muted)
export const gameAudioVolume = readonly(volume)
export const gameAudioUnlocked = readonly(unlocked)

let context: AudioContext | null = null
let masterGain: GainNode | null = null
let unlockTask: Promise<void> | null = null
let unlockAttempt = 0
let listenersAttached = false
let windowActive = true
let playbackGeneration = 0
let deferredCue: { sound: GameSound; time: number; generation: number } | null = null
const buffers = new Map<string, Promise<AudioBuffer | null>>()
const activeSources = new Set<AudioBufferSourceNode>()
const lastPlayedAt = new Map<GameSound, number>()
const singleFrames = new WeakMap<SinglePlayerState, SingleAudioFrame>()
const onlineFrames = new Map<string, GameSnapshot>()

try {
  const saved = localStorage.getItem(SETTINGS_KEY)
  if (saved) {
    const value: unknown = JSON.parse(saved)
    if (typeof value === 'object' && value !== null) {
      const settings = value as Record<string, unknown>
      if (typeof settings.muted === 'boolean') muted.value = settings.muted
      if (typeof settings.volume === 'number' && Number.isFinite(settings.volume))
        volume.value = Math.max(0, Math.min(1, settings.volume))
    }
  }
} catch {
  /* 禁用存储时仍可使用本次页面的声音设置。 */
}

function saveSettings() {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({ muted: muted.value, volume: volume.value }))
  } catch {
    /* 声音设置保存失败不影响游戏。 */
  }
}

function attachLifecycle() {
  if (listenersAttached || typeof window === 'undefined') return
  listenersAttached = true
  window.addEventListener('blur', () => {
    windowActive = false
    stopGameAudio()
  })
  window.addEventListener('focus', () => {
    windowActive = true
  })
  window.addEventListener('pagehide', stopGameAudio)
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stopGameAudio()
  })
}

function loadBuffer(file: string): Promise<AudioBuffer | null> {
  const existing = buffers.get(file)
  if (existing) return existing
  const activeContext = context
  if (!activeContext) return Promise.resolve(null)
  const task = fetch(baseUrl + file)
    .then(async (response) => {
      if (!response.ok) throw new Error('音效加载失败')
      return activeContext.decodeAudioData(await response.arrayBuffer())
    })
    .catch(() => null)
  buffers.set(file, task)
  return task
}

/** 必须从点击/按键等用户手势调用；不要求游戏等待音频加载。 */
export function unlockGameAudio(): Promise<void> {
  if (typeof window === 'undefined' || typeof AudioContext === 'undefined') return Promise.resolve()
  attachLifecycle()
  if (context?.state === 'running' && unlocked.value) return Promise.resolve()
  // 缺少用户激活时 resume() 可能一直挂起；新手势必须可以再次 resume。
  if (unlockTask && context?.state !== 'suspended') return unlockTask
  const attempt = ++unlockAttempt
  let timeoutId: ReturnType<typeof setTimeout> | undefined
  try {
    if (!context || context.state === 'closed') {
      context = new AudioContext()
      masterGain = context.createGain()
      masterGain.gain.value = muted.value ? 0 : volume.value
      masterGain.connect(context.destination)
    }
    const activeContext = context
    const resumed = activeContext.resume()
    const timeout = new Promise<void>((resolve) => {
      timeoutId = setTimeout(resolve, UNLOCK_TIMEOUT_MILLIS)
    })
    unlockTask = Promise.race([resumed, timeout])
      .then(() => {
        // 较早的恢复或超时不能覆盖后一次用户操作的状态。
        if (attempt !== unlockAttempt) return
        unlocked.value = activeContext.state === 'running'
        if (!unlocked.value) {
          deferredCue = null
          return
        }
        for (const definition of Object.values(definitions)) void loadBuffer(definition.file)
        const cue = deferredCue
        deferredCue = null
        if (cue && cue.generation === playbackGeneration && performance.now() - cue.time < 1500)
          playGameSound(cue.sound)
      })
      .catch(() => {
        if (attempt === unlockAttempt) {
          unlocked.value = false
          deferredCue = null
        }
      })
      .finally(() => {
        if (timeoutId !== undefined) clearTimeout(timeoutId)
        if (attempt === unlockAttempt) unlockTask = null
      })
    return unlockTask
  } catch {
    if (timeoutId !== undefined) clearTimeout(timeoutId)
    if (attempt === unlockAttempt) {
      unlocked.value = false
      unlockTask = null
      deferredCue = null
    }
    return Promise.resolve()
  }
}

export function setGameAudioMuted(value: boolean) {
  muted.value = value
  if (masterGain) masterGain.gain.value = value ? 0 : volume.value
  if (value) stopGameAudio()
  saveSettings()
}

export function setGameAudioVolume(value: number) {
  if (!Number.isFinite(value)) return
  volume.value = Math.max(0, Math.min(1, value))
  if (masterGain) masterGain.gain.value = muted.value ? 0 : volume.value
  if (value === 0) stopGameAudio()
  saveSettings()
}

/** 不清除事件快照，避免恢复窗口或重连时重播刚才的结算音。 */
export function stopGameAudio() {
  playbackGeneration++
  deferredCue = null
  for (const source of activeSources) {
    try {
      source.stop()
    } catch {
      /* 已播放结束。 */
    }
    source.disconnect()
  }
  activeSources.clear()
}

export function playGameSound(sound: GameSound) {
  if (
    typeof document === 'undefined' ||
    !windowActive ||
    document.hidden ||
    muted.value ||
    volume.value === 0
  )
    return
  if (!context || !masterGain || !unlocked.value || context.state !== 'running') {
    if (unlockTask && (sound === 'start' || sound === 'victory' || sound === 'defeat'))
      deferredCue = { sound, time: performance.now(), generation: playbackGeneration }
    return
  }
  const definition = definitions[sound]
  const now = performance.now()
  if (now - (lastPlayedAt.get(sound) ?? -Infinity) < definition.cooldown) return
  lastPlayedAt.set(sound, now)
  const generation = playbackGeneration
  const activeContext = context
  const destination = masterGain
  void loadBuffer(definition.file).then((buffer) => {
    if (
      !buffer ||
      generation !== playbackGeneration ||
      !windowActive ||
      document.hidden ||
      muted.value ||
      activeContext.state !== 'running' ||
      performance.now() - now > 1500
    )
      return
    // 密集交火时保留最新声音，限制同时播放数量，防止枪声无限叠加。
    if (activeSources.size >= MAX_VOICES) {
      const oldest = activeSources.values().next().value
      if (oldest) {
        try {
          oldest.stop()
        } catch {
          /* 已停止。 */
        }
        oldest.disconnect()
        activeSources.delete(oldest)
      }
    }
    const source = activeContext.createBufferSource()
    const gain = activeContext.createGain()
    source.buffer = buffer
    gain.gain.value = definition.gain
    source.connect(gain)
    gain.connect(destination)
    activeSources.add(source)
    source.onended = () => {
      activeSources.delete(source)
      source.disconnect()
      gain.disconnect()
    }
    try {
      source.start()
    } catch {
      activeSources.delete(source)
      source.disconnect()
      gain.disconnect()
    }
  })
}

/** 每个单机模拟步之后调用；只比较小快照，不扫描地图或依赖渲染频率。 */
export function observeSinglePlayerAudio(state: SinglePlayerState) {
  const current = captureSingleAudioFrame(state)
  const previous = singleFrames.get(state)
  singleFrames.set(state, current)
  for (const sound of singleAudioEvents(current, previous)) playGameSound(sound)
}

/** 只观察通过协议校验的权威快照；初次恢复已结束对局时不播放历史音效。 */
export function observeOnlineGameAudio(state: GameSnapshot, currentUserId: number) {
  const previous = onlineFrames.get(state.matchId)
  if (previous && state.tick < previous.tick) return
  onlineFrames.set(state.matchId, state)
  if (onlineFrames.size > 32) {
    const oldest = onlineFrames.keys().next().value
    if (oldest !== undefined) onlineFrames.delete(oldest)
  }
  for (const sound of onlineAudioEvents(state, previous, currentUserId)) playGameSound(sound)
}
