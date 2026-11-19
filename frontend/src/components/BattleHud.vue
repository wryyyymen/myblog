<script setup lang="ts">
import type { BattleHudState } from '@/game/battleHud'
import { BATTLE_COLORS } from '@/game/rendering/tankRenderer'

defineProps<{ state: BattleHudState | null }>()
</script>

<template>
  <header
    class="battle-hud"
    aria-label="战斗状态栏"
    :style="{
      '--health-self': BATTLE_COLORS.player,
      '--health-teammate': BATTLE_COLORS.teammate,
      '--health-opponent': BATTLE_COLORS.enemy,
      '--health-low': BATTLE_COLORS.lowHealth,
      '--health-empty': BATTLE_COLORS.emptyHealth,
    }"
  >
    <div class="battle-mission">
      <span class="battle-mode">{{ state?.mode ?? '基地防线' }}</span>
      <div class="battle-mission-details">
        <span v-if="state && state.baseAlive !== null" :class="{ 'base-lost': !state.baseAlive }">{{
          state.baseAlive ? '基地完好' : '基地被毁'
        }}</span>
        <span v-if="state && state.enemyTotal > 0"
          >击毁 {{ state.defeated }} / {{ state.enemyTotal }}</span
        >
        <span v-if="state && state.enemyTotal > 0">场上 {{ state.activeEnemies }}</span>
        <span>{{ state?.clock ?? '等待开局' }}</span>
      </div>
    </div>
    <div v-if="state" class="battle-status-right">
      <div class="battle-health-list">
        <div
          v-for="player in state.players"
          :key="player.role"
          class="battle-health-row"
          :class="player.role"
          :aria-label="`${player.role === 'self' ? '剩余血量' : player.role === 'teammate' ? '队友血量' : '对手血量'} ${player.hp}/${player.maxHp}`"
        >
          <span class="hud-label">{{
            player.role === 'self'
              ? '剩余血量'
              : player.role === 'teammate'
                ? '队友血量'
                : '对手血量'
          }}</span>
          <span class="hud-health-points" aria-hidden="true">
            <i
              v-for="point in player.maxHp"
              :key="point"
              :class="{ empty: point > player.hp, critical: player.hp === 1 }"
            ></i>
          </span>
          <strong class="hud-health-number">{{ player.hp }}/{{ player.maxHp }}</strong>
          <span v-if="player.protected" class="hud-protection">保护中</span>
        </div>
      </div>
      <div v-if="state.score !== null" class="battle-score">
        <span class="hud-label">{{ state.scoreLabel }}</span>
        <strong>{{ String(state.score).padStart(4, '0') }}</strong>
      </div>
    </div>
  </header>
</template>

<style scoped>
.battle-hud {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 14px 20px;
  padding-bottom: 16px;
  color: #dbe8dd;
}
.battle-mission {
  min-width: 0;
}
.battle-mode {
  color: #d1e3a7;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 1px;
}
.battle-mission-details {
  display: flex;
  flex-wrap: wrap;
  gap: 5px 12px;
  margin-top: 9px;
  font-size: 11px;
  color: #a9bcae;
  font-variant-numeric: tabular-nums;
}
.base-lost {
  color: #ff958a;
}
.battle-status-right {
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  gap: 18px;
  margin-left: auto;
}
.battle-health-list {
  display: grid;
  gap: 7px;
}
.battle-health-row {
  --health-color: var(--health-self);
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11px;
  white-space: nowrap;
}
.battle-health-row.teammate {
  --health-color: var(--health-teammate);
}
.battle-health-row.opponent {
  --health-color: var(--health-opponent);
}
.hud-label {
  color: #a9bcae;
  font-size: 11px;
}
.hud-health-points {
  display: inline-flex;
  gap: 3px;
}
.hud-health-points i {
  display: block;
  width: 10px;
  height: 7px;
  background: var(--health-color);
}
.hud-health-points i.critical {
  background: var(--health-low);
}
.hud-health-points i.empty {
  background: var(--health-empty);
}
.hud-health-number {
  color: var(--health-color);
  font:
    600 12px Consolas,
    monospace;
}
.hud-protection {
  color: #b1d9ff;
  font-size: 10px;
}
.battle-score {
  display: grid;
  justify-items: end;
  gap: 4px;
  min-width: 56px;
}
.battle-score strong {
  color: #ffe08a;
  font:
    700 24px/1 Consolas,
    monospace;
  font-variant-numeric: tabular-nums;
}
@media (max-width: 520px) {
  .battle-hud {
    gap: 10px;
    padding-bottom: 12px;
  }
  .battle-mission {
    flex-basis: 100%;
  }
  .battle-status-right {
    width: 100%;
  }
}
</style>
