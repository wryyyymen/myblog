<script setup lang="ts">
import BattleHud from '@/components/BattleHud.vue'
import GameAudioControls from '@/components/GameAudioControls.vue'
import type { BattleHudState } from '@/game/battleHud'

defineProps<{ hud: BattleHudState | null }>()
</script>

<template>
  <div class="battle-frame">
    <BattleHud :state="hud" />
    <div class="battle-viewport"><slot /></div>
    <footer class="battle-footer"><slot name="footer" /><GameAudioControls /></footer>
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
@media (max-width: 520px) {
  .battle-frame {
    padding: 10px;
  }
}
</style>
