<script setup lang="ts">
import {
  gameAudioMuted,
  gameAudioVolume,
  gameAudioUnlocked,
  setGameAudioMuted,
  setGameAudioVolume,
  unlockGameAudio,
} from '@/game/audio/gameAudio'

async function toggleSound() {
  if (!gameAudioUnlocked.value) {
    setGameAudioMuted(false)
    await unlockGameAudio()
    return
  }
  setGameAudioMuted(!gameAudioMuted.value)
  if (!gameAudioMuted.value) await unlockGameAudio()
}

function changeVolume(event: Event) {
  const input = event.target as HTMLInputElement
  setGameAudioVolume(Number(input.value) / 100)
  void unlockGameAudio()
}
</script>

<template>
  <div class="game-audio-controls" aria-label="游戏声音设置">
    <button
      class="audio-toggle"
      type="button"
      :aria-pressed="gameAudioUnlocked && !gameAudioMuted"
      :title="gameAudioUnlocked ? '切换游戏音效' : '点击启用游戏声音'"
      @click="toggleSound"
    >
      {{ !gameAudioUnlocked ? '开启声音' : gameAudioMuted ? '声音：关' : '声音：开' }}
    </button>
    <label class="audio-volume"
      ><span>音量</span
      ><input
        type="range"
        min="0"
        max="100"
        step="5"
        :value="Math.round(gameAudioVolume * 100)"
        aria-label="游戏音量"
        @input="changeVolume"
      />
      <span class="volume-value">{{ Math.round(gameAudioVolume * 100) }}%</span>
    </label>
  </div>
</template>

<style scoped>
.game-audio-controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  color: #a9bcae;
  font-size: 11px;
}
.audio-toggle {
  color: #dbe8dd;
  background: #273a2e;
  padding: 6px 10px;
  border: none;
  border-radius: 5px;
  font-size: 11px;
  cursor: pointer;
}
.audio-toggle:focus-visible {
  outline: 2px solid #a6ca88;
  outline-offset: 2px;
}
.audio-volume {
  display: flex;
  align-items: center;
  gap: 7px;
}
.audio-volume input {
  width: 72px;
  accent-color: #a6ca88;
  cursor: pointer;
}
.volume-value {
  min-width: 27px;
  font-variant-numeric: tabular-nums;
}
</style>
