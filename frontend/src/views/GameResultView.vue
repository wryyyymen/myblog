<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { GameResult } from '@/game/results'
import GameAudioControls from '@/components/GameAudioControls.vue'

const props = withDefaults(
  defineProps<{
    result: GameResult | null
    canRetry: boolean
    retryLabel?: string
    busy?: boolean
    notice?: string
    error?: string
    showLobby?: boolean
    canNextLevel?: boolean
  }>(),
  {
    retryLabel: '再来一局',
    busy: false,
    notice: '',
    error: '',
    showLobby: true,
    canNextLevel: false,
  },
)
defineEmits<{ retry: []; nextLevel: []; lobby: []; menu: [] }>()
const titleRef = ref<HTMLHeadingElement | null>(null)
function focusTitle() {
  if (!document.fullscreenElement) titleRef.value?.focus()
}
onMounted(() => {
  focusTitle()
  // 原生全屏退出是异步的，退出完成后再把键盘焦点交给结算标题。
  document.addEventListener('fullscreenchange', focusTitle)
})
onUnmounted(() => document.removeEventListener('fullscreenchange', focusTitle))

const title = computed(() => {
  if (!props.result) return '暂无结算记录'
  if (props.result.outcome === 'VICTORY') return props.result.mode === 'coop' ? '合作胜利' : '胜利'
  if (props.result.outcome === 'DEFEAT') return '失败'
  if (props.result.outcome === 'DRAW') return '本局平局'
  return props.result.mode === 'coop' ? '合作已中止' : '本局已中止'
})
const outcomeTag = computed(() => {
  if (!props.result) return 'BATTLE RECORD'
  if (props.result.outcome === 'VICTORY') return 'MISSION COMPLETE'
  if (props.result.outcome === 'DEFEAT') return 'GAME OVER'
  if (props.result.outcome === 'DRAW') return 'DRAW'
  return 'MISSION ABORTED'
})
const elapsed = computed(() => {
  const seconds = props.result?.elapsedSeconds ?? 0
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
})
const playerSprite = `${import.meta.env.BASE_URL}resources/png/players/tank_T2_0.png`
const teammateSprite = `${import.meta.env.BASE_URL}resources/png/players/tank_T1_0.png`
</script>

<template>
  <main class="page-container result-page">
    <section
      class="result-screen"
      :class="result?.outcome.toLowerCase()"
      aria-labelledby="result-title"
    >
      <div class="result-topline"><span>战斗结算</span><GameAudioControls /></div>
      <div class="result-emblem" aria-hidden="true">
        <span class="result-tank" :style="{ backgroundImage: `url(${playerSprite})` }"></span>
        <span
          v-if="result?.mode === 'coop'"
          class="result-tank"
          :style="{ backgroundImage: `url(${teammateSprite})` }"
        ></span>
      </div>
      <span class="result-tag">{{ outcomeTag }}</span>
      <h1 id="result-title" ref="titleRef" tabindex="-1">{{ title }}</h1>
      <p v-if="result?.levelName" class="result-level">
        第 {{ result.levelId }} 关 · {{ result.levelName
        }}<template
          v-if="result.mode === 'single' && result.levelId === 5 && result.outcome === 'VICTORY'"
        >
          · 最后一关已完成</template
        >
      </p>
      <p class="result-reason">
        {{
          result
            ? result.reason || '本局战斗已结束。'
            : '当前没有可显示的战斗结果，可以返回菜单开始新的一局。'
        }}
      </p>
      <dl v-if="result" class="result-stats">
        <div v-if="result.score !== null" class="result-score">
          <dt>{{ result.mode === 'coop' ? '团队得分' : '本局得分' }}</dt>
          <dd>{{ String(result.score).padStart(4, '0') }}</dd>
        </div>
        <div v-if="result.enemyTotal > 0">
          <dt>击毁敌军</dt>
          <dd>
            {{ result.defeated }} <small>/ {{ result.enemyTotal }}</small>
          </dd>
        </div>
        <div>
          <dt>战斗用时</dt>
          <dd>{{ elapsed }}</dd>
        </div>
        <div>
          <dt>剩余血量</dt>
          <dd>
            {{ result.remainingHp }} <small>/ {{ result.maxHp }}</small>
          </dd>
        </div>
        <div v-if="result.baseAlive !== null">
          <dt>基地状态</dt>
          <dd class="result-base">{{ result.baseAlive ? '完好' : '已被摧毁' }}</dd>
        </div>
      </dl>
      <p v-if="error" class="result-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="result-notice" role="status">{{ notice }}</p>
      <div class="result-actions">
        <button
          v-if="canNextLevel"
          class="button result-primary"
          type="button"
          :disabled="busy"
          @click="$emit('nextLevel')"
        >
          下一关
        </button>
        <button
          class="button result-primary"
          type="button"
          :disabled="!canRetry || busy"
          @click="$emit('retry')"
        >
          {{ retryLabel }}
        </button>
        <button
          v-if="showLobby && result?.mode !== 'single'"
          class="button"
          type="button"
          :disabled="busy"
          @click="$emit('lobby')"
        >
          返回大厅
        </button>
        <button class="button" type="button" :disabled="busy" @click="$emit('menu')">
          返回菜单
        </button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.result-page {
  max-width: 940px;
  padding-top: 52px;
}
.result-screen {
  --result-accent: #e9bc72;
  position: relative;
  padding: 28px 38px 38px;
  background: #15221b;
  color: #d9e6d9;
  text-align: center;
}
.result-screen.victory {
  --result-accent: #b9db85;
}
.result-screen.defeat {
  --result-accent: #f0a18b;
}
.result-topline {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  color: #9eb59f;
  font-size: 12px;
  text-align: left;
  flex-wrap: wrap;
}
.result-emblem {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  min-height: 100px;
  margin-top: 14px;
}
.result-tank {
  width: 48px;
  height: 48px;
  background-repeat: no-repeat;
  background-size: 96px 192px;
  image-rendering: pixelated;
}
.result-tag {
  color: var(--result-accent);
  font:
    11px Consolas,
    monospace;
  letter-spacing: 3px;
}
h1 {
  margin: 14px 0 18px;
  color: var(--result-accent);
  font-size: clamp(36px, 6vw, 58px);
  line-height: 1.15;
}
h1:focus {
  outline: none;
}
.result-reason {
  max-width: 560px;
  margin: 0 auto;
  color: #b6c6b8;
  font-size: 14px;
  line-height: 1.9;
  overflow-wrap: anywhere;
}
.result-level {
  color: #c3d8a0;
  font-size: 14px;
  line-height: 1.8;
}
.result-stats {
  display: flex;
  justify-content: center;
  align-items: center;
  flex-wrap: wrap;
  gap: 22px 36px;
  margin: 30px 0 0;
  padding: 26px 0;
  background: #1c2d23;
}
.result-stats > div {
  min-width: 94px;
}
.result-stats dt {
  margin-bottom: 10px;
  color: #a6bca9;
  font-size: 12px;
}
.result-stats dd {
  margin: 0;
  color: #e5eee0;
  font:
    600 24px Consolas,
    monospace;
  font-variant-numeric: tabular-nums;
}
.result-stats small {
  color: #93a795;
  font-size: 14px;
  font-weight: 400;
}
.result-score dd {
  color: #ffe08a;
  font-size: 34px;
}
.result-stats .result-base {
  font: 600 16px sans-serif;
}
.result-actions {
  display: flex;
  justify-content: center;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 30px;
}
.result-actions .button {
  min-width: 120px;
  border-color: transparent;
  background: #2c4032;
  color: #dce8d9;
}
.result-actions .result-primary {
  background: #b9d98d;
  color: #172719;
}
.result-error {
  margin-top: 20px;
  color: #ffaaa0;
  font-size: 13px;
}
.result-notice {
  margin-top: 20px;
  color: #c0d0b9;
  font-size: 13px;
  line-height: 1.8;
}
@media (any-pointer: coarse) {
  .result-actions .button {
    min-height: 48px;
    font-size: 14px;
  }
  .result-topline :deep(.audio-toggle) {
    min-height: 44px;
    font-size: 13px;
  }
}
@media (max-width: 520px) {
  .result-page {
    padding-top: 26px;
  }
  .result-screen {
    padding: 18px 16px 26px;
  }
  .result-topline {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .result-topline :deep(.audio-toggle) {
    min-height: 44px;
    font-size: 13px;
  }
  .result-topline :deep(.game-audio-controls) {
    max-width: 100%;
  }
  .result-stats {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 22px 12px;
  }
  .result-stats > div {
    min-width: 0;
  }
  .result-stats .result-score {
    grid-column: 1 / -1;
  }
  .result-stats dd {
    overflow-wrap: anywhere;
  }
  .result-actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }
  .result-actions .button {
    min-height: 48px;
    min-width: 0;
    width: 100%;
    font-size: 14px;
  }
}
</style>
