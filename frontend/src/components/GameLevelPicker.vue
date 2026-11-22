<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { gameLevels } from '@/game/maps/catalog'
import { createSinglePlayerGame } from '@/game/singleplayer/engine'
import { drawSinglePlayer } from '@/game/singleplayer/renderer'
import { loadSinglePlayerAssets } from '@/game/singleplayer/assets'

withDefaults(defineProps<{ modelValue: number; disabled?: boolean; compact?: boolean }>(), {
  disabled: false,
  compact: false,
})
defineEmits<{ 'update:modelValue': [levelId: number] }>()
const root = ref<HTMLElement | null>(null)
let disposed = false
onMounted(async () => {
  try {
    const assets = await loadSinglePlayerAssets()
    if (disposed) return
    const canvases = root.value?.querySelectorAll('canvas') ?? []
    for (const canvas of canvases) {
      const context = canvas.getContext('2d')
      if (context)
        drawSinglePlayer(context, createSinglePlayerGame(42, Number(canvas.dataset.level)), assets)
    }
  } catch {
    // 素材加载失败时仍保留关卡名称与说明，进入战场后可以重试加载。
  }
})
onUnmounted(() => {
  disposed = true
})
</script>

<template>
  <div ref="root" class="level-picker" :class="{ compact }" role="group" aria-label="选择地图关卡">
    <button
      v-for="level in gameLevels"
      :key="level.id"
      class="level-card"
      type="button"
      :data-level-id="level.id"
      :aria-pressed="modelValue === level.id"
      :disabled="disabled"
      @click="$emit('update:modelValue', level.id)"
    >
      <canvas
        v-if="!compact"
        width="624"
        height="624"
        :data-level="level.id"
        aria-hidden="true"
      ></canvas>
      <span class="level-number">关卡 {{ String(level.id).padStart(2, '0') }}</span>
      <strong>{{ level.name }}</strong>
      <span class="level-detail">{{ level.difficulty }} · {{ level.enemyTotal }} 辆敌军</span>
      <span v-if="!compact" class="level-description">{{ level.description }}</span>
    </button>
  </div>
</template>

<style scoped>
.level-picker {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}
.level-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  padding: 12px;
  text-align: left;
  color: #dce8d6;
  background: #17271f;
  border: 2px solid #3a5041;
  border-radius: 10px;
  cursor: pointer;
}
.level-card[aria-pressed='true'] {
  border-color: #c7df91;
  background: #273c2b;
  box-shadow: 0 0 0 1px #c7df9140;
}
.level-card:focus-visible {
  outline: 3px solid #ecce85;
  outline-offset: 3px;
}
.level-card:disabled {
  cursor: default;
  opacity: 0.65;
}
.level-card canvas {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 1;
  image-rendering: pixelated;
  background: #0d160f;
}
.level-number {
  color: #b9cf8f;
  font-size: 11px;
  letter-spacing: 1px;
}
.level-card strong {
  font-size: 15px;
}
.level-detail {
  color: #d6bf8b;
  font-size: 12px;
}
.level-description {
  color: #a7bbaa;
  font-size: 12px;
  line-height: 1.7;
}
.compact .level-card {
  gap: 5px;
  padding: 10px;
}
@media (max-width: 700px) {
  .level-picker {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .compact {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 380px) {
  .level-card {
    padding: 9px;
  }
}
</style>
