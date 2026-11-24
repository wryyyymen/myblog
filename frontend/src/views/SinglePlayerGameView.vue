<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, triggerRef, watch } from 'vue'
import { bindDirectionKeys } from '@/game/keyboardInput'
import { GameInputState, type InputDirection } from '@/game/inputState'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import BattleFrame from '@/components/BattleFrame.vue'
import { gameLevels, getGameLevel } from '@/game/maps/catalog'
import type { BattleHudState } from '@/game/battleHud'
import {
  createSinglePlayerGame,
  startSinglePlayerGame,
  pauseSinglePlayerGame,
  stepSinglePlayerGame,
  FIXED_STEP,
  type PlayerControls,
} from '@/game/singleplayer/engine'
import { drawSinglePlayer } from '@/game/singleplayer/renderer'
import { loadSinglePlayerAssets, type SinglePlayerAssets } from '@/game/singleplayer/assets'
import {
  clearGameResult,
  createSinglePlayerResult,
  loadGameResult,
  saveGameResult,
  updateBestScore,
  type GameResult,
} from '@/game/results'
import { observeSinglePlayerAudio, stopGameAudio, unlockGameAudio } from '@/game/audio/gameAudio'

const route = useRoute()
const router = useRouter()
const resultStorageKey = 'tank:single:last-result'
const result = shallowRef<GameResult | null>(
  route.name === 'game-single-result' ? loadGameResult(resultStorageKey) : null,
)
const showingResult = computed(() => route.name === 'game-single-result')
const restarting = ref(false)
const canvasRef = ref<HTMLCanvasElement | null>(null)
function levelIdFromRoute(): number {
  const queryId = typeof route.query.level === 'string' ? Number(route.query.level) : NaN
  if (gameLevels.some((level) => level.id === queryId)) return queryId
  if (showingResult.value && result.value?.levelId) return result.value.levelId
  return 1
}
const game = shallowRef(createSinglePlayerGame(Date.now(), levelIdFromRoute()))
const currentLevel = computed(() => getGameLevel(game.value.levelId))
const canNextLevel = computed(
  () => assetsReady.value && result.value?.outcome === 'VICTORY' && (result.value.levelId ?? 5) < 5,
)
const errorMessage = ref('')
const assetsReady = ref(false)
const controls: PlayerControls = { direction: null, fireHeld: false }
const inputState = new GameInputState()
const inputResetKey = ref(0)
const hasFocus = ref(false)
const elapsedText = computed(() => {
  const seconds = Math.floor(game.value.time)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
})
const battleHud = computed<BattleHudState>(() => ({
  mode: `第 ${game.value.levelId} 关 · ${game.value.levelName}`,
  players: [
    {
      role: 'self',
      hp: game.value.player.hp,
      maxHp: game.value.player.maxHp,
      protected:
        game.value.status === 'playing' &&
        game.value.time < game.value.player.shieldUntil &&
        game.value.player.alive,
    },
  ],
  score: game.value.score,
  scoreLabel: '得分',
  defeated: game.value.kills,
  enemyTotal: game.value.totalEnemies,
  activeEnemies: game.value.enemies.filter((enemy) => enemy.alive).length,
  baseAlive: game.value.base.alive,
  clock: `已用 ${elapsedText.value}`,
}))
const overlayTitle = computed(() => {
  if (game.value.status === 'ready') return '保卫基地'
  if (game.value.status === 'paused') return '游戏已暂停'
  if (game.value.status === 'won') return '任务完成！'
  return '游戏结束'
})
let animationId = 0
let disposed = false
let keyboardBinding: ReturnType<typeof bindDirectionKeys> | undefined
let assets: SinglePlayerAssets | null = null
let matchId = createMatchId()

function createMatchId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `single-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function showResult() {
  const ending = createSinglePlayerResult(game.value, matchId)
  if (ending === null || result.value?.matchId === ending.matchId) return
  clearInput()
  canvasRef.value?.blur()

  // 先并入历史最佳，再存本次结算：结算页要同时显示本局得分和最佳分。
  const record = updateBestScore(ending)
  const shown =
    record === null
      ? ending
      : { ...ending, bestScore: record.best.score, isNewRecord: record.isNewRecord }

  result.value = shown
  saveGameResult(resultStorageKey, shown)
  void router
    .replace({ name: 'game-single-result', query: { level: game.value.levelId } })
    .catch(() => {
      errorMessage.value = '结算页面打开失败，请返回菜单后重试。'
    })
}

function returnToMenu() {
  clearInput()
  stopGameAudio()
  void router.push({ name: 'game' })
}

async function prepareAssets() {
  errorMessage.value = ''
  try {
    const loadedAssets = await loadSinglePlayerAssets()
    if (disposed) return
    assets = loadedAssets
    assetsReady.value = true
  } catch (error) {
    if (!disposed)
      errorMessage.value = error instanceof Error ? error.message : '游戏素材加载失败，请刷新重试'
  }
}

function canControl(): boolean {
  return (
    !disposed &&
    assetsReady.value &&
    game.value.status === 'playing' &&
    !showingResult.value &&
    !document.hidden
  )
}

/** 每次输入变化时合并来源，模拟循环仍只读取现有 controls。 */
function applyInput() {
  if (!canControl()) inputState.clearAll()
  const input = inputState.read()
  controls.direction = input.direction
  controls.fireHeld = input.fireHeld
}

function clearInput() {
  // 先清来源，reset 的同步回调也不会重新读到上一局的触屏输入。
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

function focusBattlefield() {
  if (showingResult.value) return
  void unlockGameAudio()
  canvasRef.value?.focus()
}

function startOrResume() {
  if (!assetsReady.value || showingResult.value) return
  void unlockGameAudio()
  clearInput()
  if (game.value.status === 'won' || game.value.status === 'lost') {
    void restart()
    return
  }
  if (game.value.status === 'ready') {
    result.value = null
    clearGameResult(resultStorageKey)
  }
  if (game.value.status === 'paused') pauseSinglePlayerGame(game.value)
  else startSinglePlayerGame(game.value)
  observeSinglePlayerAudio(game.value)
  triggerRef(game)
  void nextTick(focusBattlefield)
}

async function restart() {
  await startLevel(
    showingResult.value ? (result.value?.levelId ?? game.value.levelId) : game.value.levelId,
  )
}

async function nextLevel() {
  if (!canNextLevel.value || !result.value?.levelId) return
  await startLevel(result.value.levelId + 1)
}

async function startLevel(levelId: number) {
  if (!assetsReady.value || restarting.value) return
  restarting.value = true
  stopGameAudio()
  void unlockGameAudio()
  clearInput()
  matchId = createMatchId()
  result.value = null
  clearGameResult(resultStorageKey)
  game.value = createSinglePlayerGame(Date.now(), levelId)
  startSinglePlayerGame(game.value)
  observeSinglePlayerAudio(game.value)
  try {
    await router.replace({ name: 'game-single', query: { level: levelId } })
    await nextTick(focusBattlefield)
  } catch {
    errorMessage.value = '新一局打开失败，请返回菜单后重试。'
  } finally {
    restarting.value = false
  }
}

function togglePause() {
  if (showingResult.value) return
  clearInput()
  pauseSinglePlayerGame(game.value)
  if (game.value.status === 'paused') stopGameAudio()
  else if (game.value.status === 'playing') {
    void unlockGameAudio()
    observeSinglePlayerAudio(game.value)
  }
  triggerRef(game)
  if (game.value.status === 'playing') void nextTick(focusBattlefield)
}

function pauseWhenAway() {
  clearInput()
  stopGameAudio()
  if (game.value.status === 'playing') {
    pauseSinglePlayerGame(game.value)
    triggerRef(game)
  }
}

function visibilityChanged() {
  if (document.hidden) pauseWhenAway()
}

function keyDown(event: KeyboardEvent) {
  if (showingResult.value) return
  if (!event.repeat) void unlockGameAudio()
  if (event.code === 'Space') {
    event.preventDefault()
    if (!event.repeat) {
      inputState.setFire('keyboard', canControl())
      applyInput()
    }
  } else if ((event.code === 'KeyP' || event.code === 'Escape') && !event.repeat) {
    event.preventDefault()
    togglePause()
  } else if (event.code === 'KeyR' && !event.repeat) {
    event.preventDefault()
    restart()
  }
}

function keyUp(event: KeyboardEvent) {
  if (event.code !== 'Space') return
  inputState.setFire('keyboard', false)
  applyInput()
}

function canvasBlur() {
  hasFocus.value = false
  // 触控按钮获焦只释放键盘，不打断另一只手仍按住的移动或射击。
  inputState.clear('keyboard')
  applyInput()
}

watch(showingResult, (showing) => {
  if (showing) clearInput()
})
watch(
  () => route.query.level,
  () => {
    if (showingResult.value) return
    const levelId = levelIdFromRoute()
    if (levelId === game.value.levelId) return
    clearInput()
    stopGameAudio()
    result.value = null
    clearGameResult(resultStorageKey)
    matchId = createMatchId()
    game.value = createSinglePlayerGame(Date.now(), levelId)
  },
)

onMounted(() => {
  const canvas = canvasRef.value
  const context = canvas?.getContext('2d')
  if (!canvas || !context) {
    errorMessage.value = '当前浏览器无法打开游戏画布'
    return
  }
  void prepareAssets()
  keyboardBinding = bindDirectionKeys(canvas, handleKeyboardDirection)
  canvas.addEventListener('keydown', keyDown)
  window.addEventListener('keyup', keyUp)
  window.addEventListener('blur', pauseWhenAway)
  document.addEventListener('visibilitychange', visibilityChanged)
  let previousTime = 0
  let accumulator = 0
  let lastHudUpdate = 0
  function frame(timestamp: number) {
    if (disposed || !context) return
    const elapsed = previousTime === 0 ? 0 : Math.min(0.1, (timestamp - previousTime) / 1000)
    previousTime = timestamp
    if (game.value.status === 'playing' && !showingResult.value) {
      const previousStatus = game.value.status
      accumulator += elapsed
      while (accumulator >= FIXED_STEP && game.value.status === 'playing') {
        stepSinglePlayerGame(game.value, controls)
        observeSinglePlayerAudio(game.value)
        accumulator -= FIXED_STEP
      }
      showResult()
      // 画布按刷新率绘制；计分、时间等 Vue 文本最多每 100ms 更新一次。
      if (timestamp - lastHudUpdate >= 100 || game.value.status !== previousStatus) {
        triggerRef(game)
        lastHudUpdate = timestamp
      }
    } else accumulator = 0
    if (assets !== null && !showingResult.value) drawSinglePlayer(context, game.value, assets)
    animationId = requestAnimationFrame(frame)
  }
  animationId = requestAnimationFrame(frame)
})

onUnmounted(() => {
  disposed = true
  cancelAnimationFrame(animationId)
  keyboardBinding?.dispose()
  clearInput()
  stopGameAudio()
  assets = null
  canvasRef.value?.removeEventListener('keydown', keyDown)
  window.removeEventListener('keyup', keyUp)
  window.removeEventListener('blur', pauseWhenAway)
  document.removeEventListener('visibilitychange', visibilityChanged)
})
</script>

<template>
  <RouterView v-if="showingResult" v-slot="{ Component }">
    <component
      :is="Component"
      :result="result"
      :can-retry="assetsReady"
      :retry-label="result ? '再来一局' : '开始游戏'"
      :can-next-level="canNextLevel"
      :busy="restarting"
      :error="errorMessage"
      :show-lobby="false"
      @retry="restart"
      @next-level="nextLevel"
      @menu="returnToMenu"
    />
  </RouterView>
  <main v-show="!showingResult" class="page-container solo-page">
    <div class="solo-heading">
      <div>
        <span class="eyebrow">单人 PVE · 经典坦克大战</span>
        <h1>第 {{ game.levelId }} 关 · {{ game.levelName }}</h1>
        <p class="muted">
          {{ currentLevel.description }} 击退 {{ game.totalEnemies }} 辆敌军，保护基地。
        </p>
      </div>
      <RouterLink class="solo-badge" to="/game">← 返回菜单</RouterLink>
    </div>
    <p v-if="errorMessage" class="notice notice-error" role="alert">{{ errorMessage }}</p>
    <div class="solo-layout">
      <section class="solo-battle" aria-label="单人坦克战场">
        <BattleFrame
          :hud="battleHud"
          :active="!showingResult"
          :controls-enabled="assetsReady && game.status === 'playing' && !showingResult"
          :input-reset-key="inputResetKey"
          @direction="handleTouchDirection"
          @fire="handleTouchFire"
        >
          <canvas
            ref="canvasRef"
            width="624"
            height="624"
            tabindex="0"
            aria-label="单人坦克大战。可用屏幕方向键和射击按钮，或WASD、方向键和空格操作，P暂停，R重开。"
            @pointerdown="focusBattlefield"
            @focus="hasFocus = true"
            @blur="canvasBlur"
          >
            当前浏览器不支持 Canvas。
          </canvas>
          <div v-if="game.status !== 'playing'" class="game-overlay">
            <div class="overlay-card" role="status" aria-live="polite">
              <span class="overlay-tag">{{
                game.status === 'won'
                  ? 'MISSION COMPLETE'
                  : game.status === 'lost'
                    ? 'GAME OVER'
                    : 'TANK DEFENSE'
              }}</span>
              <h2>{{ overlayTitle }}</h2>
              <p v-if="!assetsReady">{{ errorMessage || '正在加载游戏素材……' }}</p>
              <p v-else-if="game.status === 'ready'">
                消灭敌军，保护基地。砖墙可以击碎，钢墙无法摧毁。
              </p>
              <p v-else-if="game.status === 'paused'">战场已暂停，点击继续回到游戏。</p>
              <p v-else>{{ game.result }} · 本局得分 {{ game.score }}</p>
              <button
                class="button button-primary play-button"
                type="button"
                :disabled="!assetsReady"
                @click="startOrResume"
              >
                {{
                  !assetsReady
                    ? '素材加载中……'
                    : game.status === 'ready'
                      ? '开始游戏'
                      : game.status === 'paused'
                        ? '继续游戏'
                        : '再来一局'
                }}
              </button>
              <button
                v-if="!assetsReady && errorMessage"
                class="button"
                type="button"
                @click="prepareAssets"
              >
                重新加载素材
              </button>
            </div>
          </div>
          <template #footer>
            <span class="keyboard-hint">{{
              hasFocus ? 'WASD / 方向键移动 · 空格射击' : '点击战场后使用键盘'
            }}</span>
            <div class="battle-actions">
              <button
                class="button"
                type="button"
                :disabled="game.status !== 'playing' && game.status !== 'paused'"
                @click="togglePause"
              >
                {{ game.status === 'paused' ? '继续' : '暂停'
                }}<span class="keyboard-shortcut"> P</span>
              </button>
              <button class="button" type="button" :disabled="!assetsReady" @click="restart">
                重开<span class="keyboard-shortcut"> R</span>
              </button>
            </div>
          </template>
        </BattleFrame>
      </section>
      <aside class="solo-sidebar">
        <section class="surface mission-card controls-card">
          <h2>操作</h2>
          <p>手机：左侧方向区移动，右侧按住射击，可同时操作。横屏或全屏游玩视野更完整。</p>
          <p><kbd>W A S D</kbd> 或 <kbd>↑ ↓ ← →</kbd><span>移动与转向</span></p>
          <p><kbd>空格</kbd><span>按住连续开火</span></p>
          <p><kbd>P</kbd> / <kbd>Esc</kbd><span>暂停与继续</span></p>
          <p><kbd>R</kbd><span>重新开始</span></p>
          <p class="control-note">离开窗口会自动暂停。开始时有短暂护盾，受击后也有短暂无敌时间。</p>
        </section>
        <section class="mission-tips">
          <h2>战场提示</h2>
          <ul>
            <li>击毁本关全部 {{ game.totalEnemies }} 辆敌军获胜。</li>
            <li>你的装甲归零，或基地被摧毁，任务失败。</li>
            <li>砖墙可破坏；钢墙和河流阻挡坦克。</li>
            <li>子弹可以越过河流，树林可以提供遮挡。</li>
          </ul>
        </section>
      </aside>
    </div>
  </main>
</template>

<style scoped>
.solo-page {
  max-width: 1120px;
}
.solo-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin-bottom: 26px;
}
.solo-heading h1 {
  margin: 8px 0 10px;
  font-size: clamp(26px, 4vw, 36px);
}
.solo-heading p {
  font-size: 14px;
}
.solo-badge {
  flex-shrink: 0;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: 18px;
  color: var(--color-brand);
  background: var(--color-brand-soft);
  font-size: 12px;
}
.solo-layout {
  display: grid;
  grid-template-columns: minmax(0, 700px) 280px;
  gap: 24px;
  align-items: start;
}
.solo-battle {
  min-width: 0;
}
.battle-actions {
  display: flex;
  gap: 8px;
}
.game-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: #050b08b8;
}
.overlay-card {
  min-width: 0;
  max-width: min(340px, 100%);
  max-height: 100%;
  overflow-y: auto;
  padding: 30px 24px;
  text-align: center;
  border: 1px solid #66816588;
  border-radius: 12px;
  background: #14231df0;
  box-shadow: 0 12px 40px #0005;
  color: #eef4e9;
}
.overlay-tag {
  color: #a8c888;
  font:
    11px Consolas,
    monospace;
  letter-spacing: 2px;
}
.overlay-card h2 {
  margin: 12px 0;
  color: #f2f5eb;
  font-size: 30px;
}
.overlay-card p {
  font-size: 13px;
  line-height: 1.8;
  color: #becbbf;
}
.play-button {
  margin-top: 22px;
  min-width: min(150px, 100%);
  max-width: 100%;
}
.solo-sidebar {
  display: grid;
  gap: 18px;
}
.mission-card {
  padding: 22px;
}
.controls-card h2,
.mission-tips h2 {
  font-size: 15px;
  margin-bottom: 16px;
}
.controls-card p {
  margin-top: 14px;
  font-size: 12px;
  color: var(--color-muted);
}
.controls-card p span {
  display: block;
  margin-top: 7px;
}
kbd {
  display: inline-block;
  font:
    11px Consolas,
    monospace;
  padding: 4px 6px;
  border: 1px solid var(--color-border);
  border-bottom-width: 2px;
  border-radius: 4px;
  background: var(--color-background-soft);
  color: var(--color-heading);
}
.controls-card .control-note {
  margin-top: 20px;
  line-height: 1.8;
}
.mission-tips {
  padding: 3px 6px;
}
.mission-tips ul {
  padding-left: 18px;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.9;
}
.mission-tips li {
  margin-top: 6px;
}
@media (max-width: 1080px) {
  .solo-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .solo-sidebar {
    grid-template-columns: 1fr 1fr;
  }
  .mission-tips {
    grid-column: 1/-1;
  }
}
@media (max-width: 520px) {
  .solo-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
  .solo-sidebar {
    grid-template-columns: 1fr;
  }
  .overlay-card {
    padding: 22px 18px;
  }
  .overlay-card h2 {
    font-size: 25px;
  }
}
@media (max-height: 600px) {
  .game-overlay {
    padding: 10px;
  }
  .overlay-card {
    padding: 18px 16px;
  }
  .overlay-card h2 {
    margin: 8px 0;
    font-size: 23px;
  }
  .play-button {
    margin-top: 12px;
  }
}
</style>
