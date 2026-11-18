<script setup lang="ts">
// 结算面板。对应类图中的 ResultView 类。
//
// 两种状态共用一块面板：
//   有 result —— 显示胜负与统计
//   有 error  —— 显示可读的错误提示（不暴露错误码，对应 AC-11）
//
// 面板不自己判定胜负，只渲染传进来的视图数据（对应 AC-9）。
import type { ResultViewModel } from '@/game/result'

defineProps<{
  // 结算数据；null 表示还没有结果
  result: ResultViewModel | null
  // 错误提示；有值时优先显示错误
  error: string | null
  // 是否允许再来一局，通常只有房主可以
  canRematch?: boolean
}>()

defineEmits<{
  // 再来一局
  rematch: []
  // 返回大厅
  leave: []
}>()
</script>

<template>
  <div v-if="result || error" class="result-mask">
    <div class="result-panel" role="dialog" aria-modal="true">
      <!-- 错误态：只显示人话，不显示 code -->
      <template v-if="error">
        <h2 class="result-title result-lose">出错了</h2>
        <p class="result-detail">{{ error }}</p>
      </template>

      <!-- 结算态 -->
      <template v-else-if="result">
        <h2 class="result-title" :class="`result-${result.outcome}`">
          {{ result.title }}
        </h2>
        <p class="result-detail">{{ result.detail }}</p>

        <dl class="result-rows">
          <div v-for="row in result.rows" :key="row.label" class="result-row">
            <dt>{{ row.label }}</dt>
            <dd>{{ row.value }}</dd>
          </div>
        </dl>
      </template>

      <div class="result-actions">
        <button
          v-if="canRematch && result"
          class="button button-primary"
          type="button"
          @click="$emit('rematch')"
        >
          再来一局
        </button>
        <button class="button button-soft" type="button" @click="$emit('leave')">
          返回大厅
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.result-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(12, 16, 24, 0.72);
  border-radius: 4px;
  z-index: 5;
}

.result-panel {
  min-width: 280px;
  max-width: 420px;
  padding: 28px 32px 24px;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.35);
  text-align: center;
}

.result-title {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 500;
  letter-spacing: 2px;
}

.result-win {
  color: #b42318;
}

.result-lose {
  color: #526176;
}

.result-draw {
  color: #854f0b;
}

.result-detail {
  margin: 0 0 18px;
  font-size: 13px;
  color: #526176;
}

.result-rows {
  margin: 0 0 22px;
  padding: 0;
  border-top: 1px solid #e8ecf1;
}

.result-row {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 9px 2px;
  border-bottom: 1px solid #f1f4f8;
  font-size: 13px;
}

.result-row dt {
  color: #6b7685;
}

.result-row dd {
  margin: 0;
  color: #243247;
  font-family: ui-monospace, monospace;
}

.result-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
}

.result-actions :deep(.button) {
  min-width: 96px;
}
</style>
