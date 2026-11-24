<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import BattleFrame from '@/components/BattleFrame.vue'
import type { BattleHudState } from '@/game/battleHud'
import type { PlayerState } from '@/game/playRenderer'
import { interpolatePosition, interpolationDuration } from '@/game/network/interpolation'
import { loadSinglePlayerAssets, type SinglePlayerAssets } from '@/game/singleplayer/assets'
import { bindDirectionKeys } from '@/game/keyboardInput'
import { GameInputState, type InputDirection } from '@/game/inputState'
import { unlockGameAudio } from '@/game/audio/gameAudio'
import {
  BATTLE_COLORS,
  drawTankSprite,
  drawTankHealth,
  drawTankShield,
  type TankAppearance,
} from '@/game/rendering/tankRenderer'
import type {
  Direction,
  GameInitialization,
  GameSnapshot,
  TankSnapshot,
  BulletSnapshot,
} from '@/game/network/protocol'

const props = defineProps<{
  hud: BattleHudState | null
  initialization: GameInitialization | null
  state: GameSnapshot | null
  currentUserId: number
  playing: boolean
}>()
const emit = defineEmits<{
  loaded: [roomId: string, matchId: string]
  direction: [direction: Direction | null]
  fire: [held: boolean]
  loadError: [message: string]
}>()

interface TankTransition {
  target: TankSnapshot
  fromX: number
  fromY: number
  receivedAt: number
  duration: number
}

interface BulletTransition {
  target: BulletSnapshot
  fromX: number
  fromY: number
  receivedAt: number
  duration: number
}

const canvasRef = ref<HTMLCanvasElement | null>(null)
// 显示缩放沿用地图长宽比，画布内部坐标和联机状态不变。
const battlefieldAspectRatio = computed(() => {
  const seed = props.initialization?.map.seed
  const rows = seed?.length ?? 0
  const columns = seed?.[0]?.length ?? 0
  if (rows === 0 || columns === 0) return 1
  return columns / rows
})
const loading = ref(false)
const focused = ref(false)
const resourcesReady = ref(false)
const inputState = new GameInputState()
const inputResetKey = ref(0)
let mounted = false
let disposed = false
let loadGeneration = 0
let animationId: number | null = null
let keyboardBinding: ReturnType<typeof bindDirectionKeys> | undefined
let mapCanvas: HTMLCanvasElement | null = null
let foliageCanvas: HTMLCanvasElement | null = null
let waterCanvas: HTMLCanvasElement | null = null
let drawingContext: CanvasRenderingContext2D | null = null
let battleAssets: SinglePlayerAssets | null = null
let activeMatchId: string | null = null
let transitions: TankTransition[] = []
let bullets: BulletTransition[] = []
let previousTick: number | null = null
let previousStatus: GameSnapshot['status'] | null = null
let fireHeld = false
const explosions: { x: number; y: number; startedAt: number }[] = []
const clearedBrickCells = new Set<number>()

function canControl(): boolean {
  return !disposed && props.playing && resourcesReady.value && !document.hidden
}

function setFire(held: boolean) {
  if (fireHeld === held) return
  fireHeld = held
  emit('fire', held)
}

/** 输入来源只在这里汇总，网络发送仍由 GameView 的既有回调处理。 */
function applyInput() {
  if (!canControl()) inputState.clearAll()
  const input = inputState.read()
  // 先停火再停止移动，与现有清理顺序一致。
  setFire(input.fireHeld)
  emit('direction', input.direction)
}

function clearControls() {
  inputState.clearAll()
  keyboardBinding?.reset()
  inputResetKey.value++
  applyInput()
}

function handleKeyboardDirection(direction: InputDirection) {
  inputState.setDirection('keyboard', canControl() ? direction : null)
  applyInput()
}

function handleTouchDirection(direction: InputDirection) {
  if (direction !== null) void unlockGameAudio()
  inputState.setDirection('touch', canControl() ? direction : null)
  applyInput()
}

function handleTouchFire(held: boolean) {
  if (held) void unlockGameAudio()
  inputState.setFire('touch', canControl() && held)
  applyInput()
}

function handleFireDown(event: KeyboardEvent) {
  if (event.code !== 'Space') return
  event.preventDefault()
  if (!event.repeat) {
    inputState.setFire('keyboard', canControl())
    applyInput()
  }
}

function handleFireUp(event: KeyboardEvent) {
  if (event.code !== 'Space' || !fireHeld) return
  event.preventDefault()
  inputState.setFire('keyboard', false)
  applyInput()
}

function handleCanvasBlur() {
  focused.value = false
  // 画布失焦只清键盘；触屏由 pointerup/cancel 和全局失焦负责释放。
  inputState.clear('keyboard')
  applyInput()
}

function handleWindowBlur() {
  canvasRef.value?.blur()
  clearControls()
}

/** 地图模板保持原样，只在本局缓存上清除服务端确认被击毁的砖墙。 */
function clearDestroyedBricks(state: GameSnapshot) {
  const map = props.initialization?.map
  const context = mapCanvas?.getContext('2d')
  if (!map || !context) return
  const columns = map.seed[0]?.length ?? 0
  if (columns === 0) return
  for (const cell of state.destroyedCells) {
    if (clearedBrickCells.has(cell)) continue
    const row = Math.floor(cell / columns)
    const column = cell % columns
    if (map.seed[row]?.[column] !== 1) continue
    context.fillStyle = BATTLE_COLORS.battlefield
    context.fillRect(column * map.tileSize, row * map.tileSize, map.tileSize, map.tileSize)
    clearedBrickCells.add(cell)
  }
}

/** 只在两个已知位置间插值，不推测下一帧的位置。 */
function displayedPosition(transition: TankTransition, now: number): PlayerState {
  const progress = (now - transition.receivedAt) / transition.duration
  const position = interpolatePosition(
    { x: transition.fromX, y: transition.fromY },
    transition.target,
    transition.target.direction,
    progress,
  )
  return {
    ...position,
    size: transition.target.size,
    direction: transition.target.direction,
  }
}

function displayedBullet(transition: BulletTransition, now: number) {
  return interpolatePosition(
    { x: transition.fromX, y: transition.fromY },
    transition.target,
    transition.target.direction,
    (now - transition.receivedAt) / transition.duration,
  )
}

function receiveState(state: GameSnapshot | null, snap = false) {
  if (state === null || state.matchId !== activeMatchId) return
  if (!snap && state.tick === previousTick && state.status === previousStatus) return
  if (state.status === 'FINISHED') snap = true
  if (previousTick !== null && state.tick - previousTick > 8) snap = true
  clearDestroyedBricks(state)
  const now = performance.now()
  const tickMillis = props.initialization?.rules.tickMillis ?? 50
  const duration =
    previousTick === null ? 100 : interpolationDuration(previousTick, state.tick, tickMillis)
  const nextTransitions: TankTransition[] = []
  const allTanks: TankSnapshot[] = [...state.tanks]
  for (const enemy of state.enemies) {
    allTanks.push({ ...enemy, userId: -enemy.id, lastInputSeq: 0, shieldUntilTick: 0 })
  }
  for (const tank of allTanks) {
    let fromX = tank.x
    let fromY = tank.y
    for (const previous of transitions) {
      if (previous.target.userId !== tank.userId) continue
      if (previous.target.hp > 0 && tank.hp === 0 && !snap) {
        explosions.push({ x: tank.x + tank.size / 2, y: tank.y + tank.size / 2, startedAt: now })
      }
      const shown = displayedPosition(previous, now)
      if (!snap) {
        fromX = shown.x
        fromY = shown.y
      }
    }
    nextTransitions.push({ target: tank, fromX, fromY, receivedAt: now, duration })
  }
  transitions = nextTransitions
  const nextBullets: BulletTransition[] = []
  for (const bullet of state.bullets) {
    let fromX = bullet.x
    let fromY = bullet.y
    if (!snap) {
      const previous = bullets.find((item) => item.target.id === bullet.id)
      if (previous) {
        const shown = displayedBullet(previous, now)
        fromX = shown.x
        fromY = shown.y
      } else {
        // 新子弹已在服务端走过至少一个 tick，沿已通过碰撞检查的路径补出这一段。
        const distance = ((props.initialization?.rules.bulletSpeed ?? 360) * tickMillis) / 1000
        if (bullet.direction === 0) fromY += distance
        else if (bullet.direction === 1) fromY -= distance
        else if (bullet.direction === 2) fromX += distance
        else fromX -= distance
        fromX = Math.max(0, fromX)
        fromY = Math.max(0, fromY)
      }
    }
    nextBullets.push({ target: bullet, fromX, fromY, receivedAt: now, duration })
  }
  bullets = nextBullets
  previousTick = state.tick
  previousStatus = state.status
  const ownTank = state.tanks.find((tank) => tank.userId === props.currentUserId)
  if (state.status === 'FINISHED' || ownTank?.hp === 0) {
    canvasRef.value?.blur()
    clearControls()
  }
}

async function loadScene(initialization: GameInitialization | null) {
  const generation = ++loadGeneration
  resourcesReady.value = false
  loading.value = initialization !== null
  activeMatchId = null
  transitions = []
  explosions.length = 0
  bullets = []
  clearedBrickCells.clear()
  mapCanvas = null
  foliageCanvas = null
  waterCanvas = null
  previousTick = null
  previousStatus = null
  battleAssets = null
  canvasRef.value?.blur()
  clearControls()
  if (initialization === null) return

  try {
    await nextTick()
    const canvas = canvasRef.value
    const context = drawingContext
    if (!canvas || !context) throw new Error('浏览器无法创建游戏画布')
    const columns = initialization.map.seed[0]?.length ?? 0
    const width = columns * initialization.map.tileSize
    const height = initialization.map.seed.length * initialization.map.tileSize
    canvas.width = width
    canvas.height = height
    context.imageSmoothingEnabled = false
    const background = document.createElement('canvas')
    background.width = width
    background.height = height
    const backgroundContext = background.getContext('2d')
    if (!backgroundContext) throw new Error('无法加载地图背景')

    const foliage = document.createElement('canvas')
    foliage.width = width
    foliage.height = height
    const foliageContext = foliage.getContext('2d')
    if (!foliageContext) throw new Error('无法加载树林背景')

    const water = document.createElement('canvas')
    water.width = width
    water.height = height
    const waterContext = water.getContext('2d')
    if (!waterContext) throw new Error('无法加载水面背景')

    // 资源只在本局开始时加载，后续帧复用地图缓存和坦克图片。
    const assets = await loadSinglePlayerAssets()
    if (disposed || generation !== loadGeneration) return
    backgroundContext.imageSmoothingEnabled = false
    backgroundContext.fillStyle = BATTLE_COLORS.battlefield
    backgroundContext.fillRect(0, 0, width, height)
    foliageContext.imageSmoothingEnabled = false
    waterContext.imageSmoothingEnabled = false
    for (let row = 0; row < initialization.map.seed.length; row++) {
      const cells = initialization.map.seed[row]
      if (!cells) continue
      for (let column = 0; column < cells.length; column++) {
        const material = cells[column] ?? 0
        if (material === 0) continue
        const image = assets.tiles[material]
        if (!image) throw new Error('地图包含未知地形')
        const layer = material === 4 ? foliageContext : backgroundContext
        layer.drawImage(
          image,
          column * initialization.map.tileSize,
          row * initialization.map.tileSize,
          initialization.map.tileSize,
          initialization.map.tileSize,
        )
        if (material === 3 && assets.waterFrames[1]) {
          waterContext.drawImage(
            assets.waterFrames[1],
            column * initialization.map.tileSize,
            row * initialization.map.tileSize,
            initialization.map.tileSize,
            initialization.map.tileSize,
          )
        }
      }
    }
    mapCanvas = background
    foliageCanvas = foliage
    waterCanvas = water
    battleAssets = assets
    activeMatchId = initialization.matchId
    resourcesReady.value = true
    loading.value = false
    const latestState =
      props.state?.matchId === initialization.matchId ? props.state : initialization.state
    receiveState(latestState, true)
    console.log('[GameBattleCanvas.loadScene] 地图与坦克加载完成，matchId =', activeMatchId)
    emit('loaded', initialization.roomId, initialization.matchId)
  } catch (error) {
    if (disposed || generation !== loadGeneration) return
    loading.value = false
    emit('loadError', error instanceof Error ? error.message : '游戏资源加载失败')
  }
}

function drawFrame(now: number) {
  if (disposed) return
  const canvas = canvasRef.value
  const context = drawingContext
  if (canvas && context) {
    if (mapCanvas && battleAssets) {
      context.drawImage(mapCanvas, 0, 0)
      const state = props.state
      const tickMillis = props.initialization?.rules.tickMillis ?? 50
      const elapsedAfterSnapshot =
        state?.status === 'PLAYING'
          ? Math.max(0, Math.min(100, now - (transitions[0]?.receivedAt ?? now)))
          : 0
      const animationTime = ((state?.tick ?? 0) * tickMillis + elapsedAfterSnapshot) / 1000
      if (waterCanvas && Math.floor(animationTime / 0.3) % 2 === 1) {
        context.drawImage(waterCanvas, 0, 0)
      }
      const base = props.state?.base
      if (base) {
        context.drawImage(
          base.alive ? battleAssets.base : battleAssets.destroyedBase,
          base.x,
          base.y,
          base.size,
          base.size,
        )
      }
      for (const transition of transitions) {
        if (transition.target.hp === 0) continue
        const player = displayedPosition(transition, now)
        const isMe = transition.target.userId === props.currentUserId
        const isEnemy = transition.target.userId < 0 || (!isMe && props.state?.mode === 'PVP')
        const tankImage = isMe
          ? battleAssets.player
          : isEnemy
            ? battleAssets.enemy
            : battleAssets.teammate
        drawTankSprite(context, player, tankImage, animationTime)
      }
      if (foliageCanvas) context.drawImage(foliageCanvas, 0, 0)
      // 与单人模式一致，血条和护盾在树林之后绘制，不再一端在上、一端在下。
      for (const transition of transitions) {
        if (transition.target.hp === 0) continue
        const player = displayedPosition(transition, now)
        const isMe = transition.target.userId === props.currentUserId
        const isEnemy = transition.target.userId < 0 || (!isMe && state?.mode === 'PVP')
        const appearance: TankAppearance = isMe ? 'player' : isEnemy ? 'enemy' : 'teammate'
        const initialHp =
          transition.target.userId < 0 ? 1 : (props.initialization?.rules.initialHp ?? 3)
        drawTankHealth(context, player, transition.target.hp, initialHp, appearance)
        if (
          transition.target.userId > 0 &&
          (state?.tick ?? 0) < transition.target.shieldUntilTick
        ) {
          drawTankShield(context, player, battleAssets.shield, animationTime)
        }
      }
      for (const transition of bullets) {
        const bullet = displayedBullet(transition, now)
        context.drawImage(
          battleAssets.bullets[transition.target.direction],
          Math.round(bullet.x - 3),
          Math.round(bullet.y - 3),
          6,
          6,
        )
      }
      for (let index = explosions.length - 1; index >= 0; index--) {
        const effect = explosions[index]
        if (!effect) continue
        const frame = Math.floor((now - effect.startedAt) / 70)
        if (frame >= 6) {
          explosions.splice(index, 1)
          continue
        }
        context.drawImage(
          battleAssets.explosion,
          frame * 96,
          0,
          96,
          96,
          Math.round(effect.x - 22),
          Math.round(effect.y - 22),
          44,
          44,
        )
      }
    } else {
      context.fillStyle = BATTLE_COLORS.battlefield
      context.fillRect(0, 0, canvas.width, canvas.height)
    }
  }
  animationId = requestAnimationFrame(drawFrame)
}

function focusCanvas() {
  if (props.playing && resourcesReady.value) canvasRef.value?.focus()
}

function handleVisibilityChange() {
  if (document.hidden) {
    canvasRef.value?.blur()
    clearControls()
  }
}

watch(
  () => props.initialization,
  (initialization) => {
    if (mounted) void loadScene(initialization)
  },
)
watch(
  () => props.state,
  (state) => receiveState(state),
)
watch(
  () => props.playing,
  (playing) => {
    if (!playing) {
      canvasRef.value?.blur()
      clearControls()
    }
  },
)

onMounted(() => {
  mounted = true
  const canvas = canvasRef.value
  if (canvas) {
    drawingContext = canvas.getContext('2d', { alpha: false })
    keyboardBinding = bindDirectionKeys(canvas, handleKeyboardDirection)
    canvas.addEventListener('keydown', handleFireDown)
  }
  window.addEventListener('keyup', handleFireUp)
  window.addEventListener('blur', handleWindowBlur)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  animationId = requestAnimationFrame(drawFrame)
  void loadScene(props.initialization)
})

onUnmounted(() => {
  disposed = true
  ++loadGeneration
  keyboardBinding?.dispose()
  clearControls()
  canvasRef.value?.removeEventListener('keydown', handleFireDown)
  window.removeEventListener('keyup', handleFireUp)
  window.removeEventListener('blur', handleWindowBlur)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  if (animationId !== null) cancelAnimationFrame(animationId)
  mapCanvas = null
  foliageCanvas = null
  waterCanvas = null
  battleAssets = null
  drawingContext = null
})
</script>

<template>
  <section class="battle-area" aria-label="双人坦克战场">
    <p v-if="loading" class="canvas-note" role="status">正在加载地图和坦克……</p>
    <p v-else-if="playing" class="canvas-note" :class="{ 'is-focused': focused }" role="status">
      {{
        focused
          ? '触屏可用方向区和射击按钮；键盘已就绪。'
          : '手机使用方向区和射击按钮；电脑点击战场后使用 WASD / 方向键和空格。'
      }}
    </p>
    <p v-else-if="state?.status === 'FINISHED'" class="canvas-note">本局已结束，最终战场已保留。</p>
    <p v-else class="canvas-note">开局后在这里控制你的坦克。</p>
    <BattleFrame
      :hud="hud"
      :aspect-ratio="battlefieldAspectRatio"
      :controls-enabled="playing && resourcesReady"
      :input-reset-key="inputResetKey"
      @direction="handleTouchDirection"
      @fire="handleTouchFire"
    >
      <canvas
        ref="canvasRef"
        width="624"
        height="624"
        tabindex="0"
        aria-label="坦克战场。可使用屏幕方向区移动、按住射击按钮，或点击画布使用WASD、方向键和空格操作。"
        @pointerdown="focusCanvas"
        @focus="focused = true"
        @blur="handleCanvasBlur"
        >当前浏览器不支持 Canvas。</canvas
      >
      <template #footer
        ><span class="keyboard-hint">WASD / 方向键移动 · 空格射击</span
        ><span class="keyboard-hint">{{
          focused ? '键盘已就绪' : '点击战场后使用键盘'
        }}</span></template
      >
    </BattleFrame>
  </section>
</template>

<style scoped>
.battle-area {
  min-width: 0;
}
canvas {
  display: block;
  width: 100%;
  height: auto;
  background: #10140f;
  image-rendering: pixelated;
  border: none;
  border-radius: 0;
  outline: none;
}
.canvas-note {
  margin: 0 0 12px;
  color: var(--color-muted);
  font-size: 13px;
}
.is-focused {
  color: var(--color-brand);
}
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin-top: 12px;
  color: var(--color-muted);
  font-size: 12px;
}
</style>
