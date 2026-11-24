<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { InputDirection } from '@/game/inputState'
import { unlockGameAudio } from '@/game/audio/gameAudio'

const props = defineProps<{
  visible: boolean
  enabled: boolean
  resetKey: string | number
}>()
const emit = defineEmits<{
  direction: [direction: InputDirection]
  fire: [held: boolean]
}>()

const directionPadRef = ref<HTMLDivElement | null>(null)
const fireButtonRef = ref<HTMLButtonElement | null>(null)
const currentDirection = ref<InputDirection>(null)
const firing = ref(false)
const directions = [
  { value: 0, label: '向上', symbol: '▲', position: 'up' },
  { value: 1, label: '向下', symbol: '▼', position: 'down' },
  { value: 2, label: '向左', symbol: '◀', position: 'left' },
  { value: 3, label: '向右', symbol: '▶', position: 'right' },
]
let directionPointerId: number | null = null
const firePointerIds = new Set<number>()
let landscape = false

function setDirection(direction: InputDirection) {
  if (currentDirection.value === direction) return
  currentDirection.value = direction
  emit('direction', direction)
}

function updateFire() {
  const held = firePointerIds.size > 0
  if (firing.value === held) return
  firing.value = held
  emit('fire', held)
}

function releaseCapture(element: HTMLElement | null, pointerId: number) {
  if (element?.hasPointerCapture(pointerId)) element.releasePointerCapture(pointerId)
}

/** 方向区支持按住后滑动换向，移出控制区或回到中心即停止。 */
function directionAt(event: PointerEvent): InputDirection {
  const pad = directionPadRef.value
  if (!pad) return null
  const box = pad.getBoundingClientRect()
  if (
    event.clientX < box.left ||
    event.clientX > box.right ||
    event.clientY < box.top ||
    event.clientY > box.bottom
  )
    return null
  const x = event.clientX - box.left - box.width / 2
  const y = event.clientY - box.top - box.height / 2
  if (Math.max(Math.abs(x), Math.abs(y)) < box.width * 0.17) return null
  if (Math.abs(x) > Math.abs(y)) return x < 0 ? 2 : 3
  return y < 0 ? 0 : 1
}

function directionDown(event: PointerEvent) {
  if (!props.visible || !props.enabled || directionPointerId !== null || event.button > 0) return
  event.preventDefault()
  void unlockGameAudio()
  directionPointerId = event.pointerId
  directionPadRef.value?.setPointerCapture(event.pointerId)
  setDirection(directionAt(event))
}

function directionMove(event: PointerEvent) {
  if (directionPointerId !== event.pointerId) return
  setDirection(directionAt(event))
}

function directionEnd(event: PointerEvent) {
  if (directionPointerId !== event.pointerId) return
  directionPointerId = null
  setDirection(null)
  releaseCapture(directionPadRef.value, event.pointerId)
}

function fireDown(event: PointerEvent) {
  if (!props.visible || !props.enabled || event.button > 0) return
  event.preventDefault()
  void unlockGameAudio()
  firePointerIds.add(event.pointerId)
  fireButtonRef.value?.setPointerCapture(event.pointerId)
  updateFire()
}

function fireEnd(event: PointerEvent) {
  if (!firePointerIds.delete(event.pointerId)) return
  updateFire()
  releaseCapture(fireButtonRef.value, event.pointerId)
}

/** 单指抬起只释放该操作；离开游戏时才释放全部触点。 */
function clearPointers() {
  const directionId = directionPointerId
  const fireIds = Array.from(firePointerIds)
  directionPointerId = null
  firePointerIds.clear()
  setDirection(null)
  updateFire()
  if (directionId !== null) releaseCapture(directionPadRef.value, directionId)
  for (const pointerId of fireIds) releaseCapture(fireButtonRef.value, pointerId)
}

function visibilityChanged() {
  if (document.hidden) clearPointers()
}

function orientationChanged() {
  const nextLandscape = window.innerWidth > window.innerHeight
  if (nextLandscape !== landscape) clearPointers()
  landscape = nextLandscape
}

defineExpose({ reset: clearPointers })

watch([() => props.visible, () => props.enabled, () => props.resetKey], clearPointers)
onMounted(() => {
  landscape = window.innerWidth > window.innerHeight
  window.addEventListener('blur', clearPointers)
  window.addEventListener('pagehide', clearPointers)
  window.addEventListener('resize', orientationChanged)
  document.addEventListener('visibilitychange', visibilityChanged)
})
onBeforeUnmount(() => {
  window.removeEventListener('blur', clearPointers)
  window.removeEventListener('pagehide', clearPointers)
  window.removeEventListener('resize', orientationChanged)
  document.removeEventListener('visibilitychange', visibilityChanged)
  clearPointers()
})
</script>

<template>
  <div class="touch-play-area" :class="{ 'has-touch-controls': visible }">
    <div class="touch-stage"><slot /></div>
    <div
      v-if="visible"
      ref="directionPadRef"
      class="touch-direction-pad"
      :class="{ 'is-disabled': !enabled }"
      role="group"
      aria-label="触屏方向控制，按住移动，滑动换向，松手停止"
      @pointerdown="directionDown"
      @pointermove="directionMove"
      @pointerup="directionEnd"
      @pointercancel="directionEnd"
      @lostpointercapture="directionEnd"
      @contextmenu.prevent
    >
      <button
        v-for="direction in directions"
        :key="direction.value"
        class="touch-direction-button"
        :class="[direction.position, { pressed: currentDirection === direction.value }]"
        :data-direction="direction.value"
        :aria-label="direction.label"
        :aria-pressed="currentDirection === direction.value"
        :disabled="!enabled"
        type="button"
        tabindex="-1"
      >
        <span aria-hidden="true">{{ direction.symbol }}</span>
      </button>
      <span class="touch-pad-center" aria-hidden="true"></span>
    </div>
    <div v-if="visible" class="touch-fire-area">
      <button
        ref="fireButtonRef"
        class="touch-fire-button"
        :class="{ pressed: firing }"
        type="button"
        tabindex="-1"
        aria-label="射击，按住连续开火，松手停止"
        :aria-pressed="firing"
        :disabled="!enabled"
        @pointerdown="fireDown"
        @pointerup="fireEnd"
        @pointercancel="fireEnd"
        @lostpointercapture="fireEnd"
        @contextmenu.prevent
      >
        <span>射击</span><small>按住连发</small>
      </button>
    </div>
  </div>
</template>

<style scoped>
.touch-play-area {
  display: flex;
  min-width: 0;
  min-height: 0;
}
.touch-stage {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.has-touch-controls {
  --pad-size: 144px;
  display: grid;
  grid-template-areas: 'stage stage' 'direction fire';
  grid-template-columns: 1fr 1fr;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: 10px;
}
.has-touch-controls .touch-stage {
  grid-area: stage;
}
.touch-direction-pad {
  grid-area: direction;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  grid-template-rows: repeat(3, 1fr);
  width: var(--pad-size);
  height: var(--pad-size);
  align-self: center;
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}
.touch-direction-button {
  min-width: 0;
  min-height: 0;
  padding: 0;
  border: 1px solid #54715a;
  border-radius: 10px;
  background: #293f31;
  color: #d7e8c4;
  font-size: 21px;
  touch-action: none;
}
.touch-direction-button.up {
  grid-area: 1 / 2;
}
.touch-direction-button.down {
  grid-area: 3 / 2;
}
.touch-direction-button.left {
  grid-area: 2 / 1;
}
.touch-direction-button.right {
  grid-area: 2 / 3;
}
.touch-pad-center {
  grid-area: 2 / 2;
  margin: 8px;
  border-radius: 50%;
  background: #203027;
  pointer-events: none;
}
.touch-fire-area {
  grid-area: fire;
  display: flex;
  justify-content: flex-end;
  align-items: center;
}
.touch-fire-button {
  display: grid;
  place-content: center;
  gap: 4px;
  width: 96px;
  height: 96px;
  padding: 0;
  border: 2px solid #b59755;
  border-radius: 50%;
  background: #5b4827;
  color: #ffe9af;
  font-size: 20px;
  font-weight: 700;
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}
.touch-fire-button small {
  font-size: 11px;
  font-weight: 400;
}
.pressed {
  background: #526e3a;
  color: #fff8d5;
  border-color: #b8d58e;
}
.is-disabled {
  opacity: 0.5;
}
@media (max-width: 360px) {
  .has-touch-controls {
    --pad-size: 132px;
    gap: 6px;
  }
  .touch-fire-button {
    width: 86px;
    height: 86px;
  }
}
@media (orientation: landscape) and (max-height: 600px) {
  .has-touch-controls {
    --pad-size: clamp(126px, 28vh, 144px);
    grid-template-areas: 'direction stage fire';
    grid-template-columns: var(--pad-size) minmax(0, 1fr) 96px;
    grid-template-rows: minmax(0, 1fr);
    gap: 10px;
  }
  .touch-fire-button {
    width: 86px;
    height: 86px;
  }
}
</style>
