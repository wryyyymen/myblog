<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import BattleHud from '@/components/BattleHud.vue'
import GameAudioControls from '@/components/GameAudioControls.vue'
import GameTouchControls from '@/components/GameTouchControls.vue'
import type { BattleHudState } from '@/game/battleHud'
import type { InputDirection } from '@/game/inputState'
import { shouldShowTouchControlsByDefault } from '@/game/touchDefaults'

const props = withDefaults(
  defineProps<{
    hud: BattleHudState | null
    aspectRatio?: number
    active?: boolean
    controlsEnabled?: boolean
    inputResetKey?: string | number
  }>(),
  { aspectRatio: 1, active: true, controlsEnabled: false, inputResetKey: 0 },
)
const emit = defineEmits<{
  direction: [direction: InputDirection]
  fire: [held: boolean]
}>()

const frameRef = ref<HTMLDivElement | null>(null)
const viewportRef = ref<HTMLDivElement | null>(null)
const touchControlsRef = ref<InstanceType<typeof GameTouchControls> | null>(null)
const defaultTouchControls = ref(false)
const touchOverride = ref<boolean | null>(null)
const windowBattleHeight = ref(760)
const showTouchControls = computed(() => touchOverride.value ?? defaultTouchControls.value)
const nativeFullscreen = ref(false)
const windowFullscreen = ref(false)
const switchingFullscreen = ref(false)
const stageWidth = ref(0)
const stageHeight = ref(0)
const isFullscreen = computed(() => nativeFullscreen.value || windowFullscreen.value)
const fitToArea = computed(() => isFullscreen.value || showTouchControls.value)
const frameStyle = computed(() => {
  if (!showTouchControls.value || isFullscreen.value) return undefined
  return { height: `${windowBattleHeight.value}px` }
})
const stageStyle = computed(() => {
  if (!fitToArea.value) return undefined
  return { width: `${stageWidth.value}px`, height: `${stageHeight.value}px` }
})
let resizeObserver: ResizeObserver | undefined
let previousBodyOverflow = ''
let bodyScrollLocked = false
let disposed = false

function updateAvailableSpace() {
  // 窗口模式也为手机留出完整操作区；原生全屏时由全屏容器决定高度。
  windowBattleHeight.value = Math.max(
    340,
    Math.min(920, (window.visualViewport?.height ?? window.innerHeight) - 24),
  )
  fitStage()
}

function initializeTouchControls() {
  const deviceNavigator = navigator as Navigator & { userAgentData?: { mobile?: boolean } }
  defaultTouchControls.value = shouldShowTouchControlsByDefault({
    userAgent: deviceNavigator.userAgent,
    platform: deviceNavigator.platform,
    maxTouchPoints: deviceNavigator.maxTouchPoints,
    mobileHint: deviceNavigator.userAgentData?.mobile,
    coarsePointer: window.matchMedia('(pointer: coarse)').matches,
    hoverNone: window.matchMedia('(hover: none)').matches,
  })
}

function toggleTouchControls() {
  touchOverride.value = !showTouchControls.value
}

/** 只缩放显示尺寸，保留画布坐标、移动速度和碰撞计算使用的逻辑分辨率。 */
function fitStage() {
  const viewport = viewportRef.value
  if (!viewport || !fitToArea.value) return
  const aspectRatio =
    Number.isFinite(props.aspectRatio) && props.aspectRatio > 0 ? props.aspectRatio : 1
  const width = Math.min(viewport.clientWidth, viewport.clientHeight * aspectRatio)
  stageWidth.value = width
  stageHeight.value = width / aspectRatio
}

function focusBattlefield() {
  if (disposed || !props.active) return
  frameRef.value?.querySelector('canvas')?.focus({ preventScroll: true })
}

function restoreBodyScroll() {
  if (!bodyScrollLocked) return
  document.body.style.overflow = previousBodyOverflow
  bodyScrollLocked = false
}

function fullscreenChanged() {
  touchControlsRef.value?.reset()
  nativeFullscreen.value = document.fullscreenElement === frameRef.value
}

async function toggleFullscreen() {
  const frame = frameRef.value
  if (!frame || !props.active || switchingFullscreen.value) return
  switchingFullscreen.value = true
  try {
    if (document.fullscreenElement === frame) {
      await document.exitFullscreen()
    } else if (windowFullscreen.value) {
      windowFullscreen.value = false
    } else {
      // 不支持原生全屏或被浏览器限制时，仍可使用铺满窗口的战场。
      if (document.fullscreenEnabled && typeof frame.requestFullscreen === 'function') {
        try {
          await frame.requestFullscreen()
          if (disposed || !props.active) leaveFullscreen()
          return
        } catch {
          if (disposed || !props.active) return
        }
      }
      windowFullscreen.value = true
    }
  } finally {
    switchingFullscreen.value = false
  }
}

function leaveFullscreen() {
  windowFullscreen.value = false
  if (document.fullscreenElement === frameRef.value) {
    void document.exitFullscreen().catch(() => {})
  }
}

function escapeWindowFullscreen(event: KeyboardEvent) {
  if (event.code !== 'Escape' || !windowFullscreen.value) return
  event.preventDefault()
  // 退出窗口全屏时不再把同一次 Esc 传给单人模式的暂停处理。
  event.stopPropagation()
  windowFullscreen.value = false
}

watch(windowFullscreen, (fullscreen) => {
  touchControlsRef.value?.reset()
  if (fullscreen) {
    previousBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    bodyScrollLocked = true
  } else restoreBodyScroll()
})
watch(isFullscreen, async () => {
  await nextTick()
  if (disposed) return
  fitStage()
  focusBattlefield()
})
watch(() => props.aspectRatio, fitStage)
watch(showTouchControls, async () => {
  await nextTick()
  if (!disposed) updateAvailableSpace()
})
// 单人结算使用嵌套路由，战场仍挂载但被隐藏，因此也要主动退出全屏。
watch(
  () => props.active,
  (active) => {
    if (!active) leaveFullscreen()
  },
)

onMounted(() => {
  // 默认值只在挂载时确定，旋转屏幕、调整窗口或触摸电脑屏幕不会覆盖手动选择。
  initializeTouchControls()
  updateAvailableSpace()
  resizeObserver = new ResizeObserver(fitStage)
  if (viewportRef.value) resizeObserver.observe(viewportRef.value)
  document.addEventListener('fullscreenchange', fullscreenChanged)
  window.addEventListener('keydown', escapeWindowFullscreen, true)
  window.addEventListener('resize', updateAvailableSpace)
  window.visualViewport?.addEventListener('resize', updateAvailableSpace)
})

onBeforeUnmount(() => {
  disposed = true
  resizeObserver?.disconnect()
  document.removeEventListener('fullscreenchange', fullscreenChanged)
  window.removeEventListener('keydown', escapeWindowFullscreen, true)
  window.removeEventListener('resize', updateAvailableSpace)
  window.visualViewport?.removeEventListener('resize', updateAvailableSpace)
  restoreBodyScroll()
  leaveFullscreen()
})
</script>

<template>
  <div
    ref="frameRef"
    class="battle-frame"
    :class="{
      'is-fullscreen': isFullscreen,
      'has-touch-controls': showTouchControls,
      'fits-area': fitToArea,
    }"
    :style="frameStyle"
  >
    <BattleHud :state="hud" />
    <GameTouchControls
      ref="touchControlsRef"
      :visible="showTouchControls"
      :enabled="active && controlsEnabled"
      :reset-key="inputResetKey"
      @direction="emit('direction', $event)"
      @fire="emit('fire', $event)"
    >
      <div ref="viewportRef" class="battle-viewport">
        <div class="battle-stage" :style="stageStyle"><slot /></div>
      </div>
    </GameTouchControls>
    <footer class="battle-footer">
      <slot name="footer" />
      <GameAudioControls />
      <button
        class="button touch-toggle"
        type="button"
        :aria-pressed="showTouchControls"
        :aria-label="showTouchControls ? '收起触屏控制' : '显示触屏控制'"
        @click="toggleTouchControls"
      >
        <span class="touch-toggle-label">{{ showTouchControls ? '收起触控' : '触控' }}</span>
        <span class="touch-toggle-short" aria-hidden="true">触控</span>
      </button>
      <button
        class="button fullscreen-button"
        type="button"
        :aria-pressed="isFullscreen"
        :disabled="switchingFullscreen"
        :title="isFullscreen ? '退出全屏（Esc）' : '全屏游玩'"
        @click="toggleFullscreen"
      >
        {{ isFullscreen ? '退出全屏' : '全屏' }}
      </button>
    </footer>
  </div>
</template>

<style scoped>
.battle-frame {
  width: 100%;
  max-width: 656px;
  min-width: 0;
  margin-inline: auto;
  padding: 16px;
  background: #18231d;
  color: #dbe8dd;
}
.battle-viewport {
  min-width: 0;
  min-height: 0;
}
.battle-frame.has-touch-controls {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
}
.fits-area :deep(.touch-stage) {
  display: flex;
}
.fits-area .battle-viewport {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  overflow: hidden;
}
.fits-area .battle-stage :deep(canvas) {
  width: 100%;
  height: 100%;
}
.battle-stage {
  position: relative;
  width: 100%;
  background: #10140f;
}
.battle-viewport :deep(canvas) {
  display: block;
  width: 100%;
  height: auto;
  max-height: none;
  border: none;
  border-radius: 0;
  outline: none;
  image-rendering: pixelated;
}
.battle-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 16px;
  padding-top: 12px;
  color: #a9bcae;
  font-size: 11px;
  line-height: 1.7;
}
.battle-footer :deep(.button) {
  padding: 6px 10px;
  font-size: 11px;
  color: #dbe8dd;
  background: #273a2e;
  border-color: transparent;
}
.fullscreen-button {
  flex-shrink: 0;
  margin-left: auto;
}
.touch-toggle {
  flex-shrink: 0;
}
.touch-toggle-short {
  display: none;
}
.has-touch-controls .battle-footer :deep(.keyboard-hint) {
  display: none;
}
.has-touch-controls .battle-footer :deep(.keyboard-shortcut) {
  display: none;
}
.battle-frame.is-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  width: 100%;
  height: 100%;
  height: 100dvh;
  max-width: none;
  margin: 0;
  padding: max(10px, env(safe-area-inset-top)) max(12px, env(safe-area-inset-right))
    max(10px, env(safe-area-inset-bottom)) max(12px, env(safe-area-inset-left));
  overflow: hidden;
}
.is-fullscreen .battle-viewport {
  display: grid;
  place-items: center;
  overflow: hidden;
}
.is-fullscreen .battle-stage :deep(canvas) {
  width: 100%;
  height: 100%;
}
.is-fullscreen .battle-footer {
  gap: 8px 16px;
  padding-top: 10px;
}
@media (max-height: 520px) and (min-width: 521px) {
  .is-fullscreen :deep(.battle-hud) {
    gap: 8px 16px;
    padding-bottom: 8px;
  }
  .is-fullscreen :deep(.battle-mission-details) {
    margin-top: 3px;
  }
  .is-fullscreen .battle-footer {
    padding-top: 6px;
  }
}
@media (max-width: 520px) {
  .battle-frame {
    padding: 10px;
  }
  .has-touch-controls :deep(.battle-hud) {
    gap: 6px;
    padding-bottom: 8px;
  }
  .has-touch-controls :deep(.battle-mission-details) {
    margin-top: 3px;
  }
  .has-touch-controls :deep(.battle-mode) {
    font-size: 12px;
    letter-spacing: 0;
  }
  .has-touch-controls :deep(.battle-score strong) {
    font-size: 20px;
  }
  .has-touch-controls .battle-footer {
    gap: 6px;
    padding-top: 8px;
  }
  .has-touch-controls .battle-footer > :deep(span) {
    display: none;
  }
  .has-touch-controls .battle-footer :deep(.button),
  .has-touch-controls .battle-footer :deep(.audio-toggle) {
    min-height: 40px;
    min-width: 44px;
    padding: 6px 8px;
  }
  .touch-toggle-label {
    display: none;
  }
  .touch-toggle-short {
    display: inline;
  }
  .has-touch-controls .battle-footer :deep(.audio-volume) {
    display: none;
  }
}
</style>
