<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ seed?: number }>(), { seed: 0 })
const palette = computed(() => ['sage', 'sand', 'blue'][Math.abs(props.seed) % 3] ?? 'sage')
</script>

<template>
  <!-- 本地 SVG 插画：无封面文章也能保持完整布局，不依赖外部图片服务。 -->
  <svg
    class="blog-artwork"
    :class="palette"
    viewBox="0 0 400 260"
    fill="none"
    aria-hidden="true"
    focusable="false"
    preserveAspectRatio="xMidYMid slice"
  >
    <rect width="400" height="260" fill="var(--art-bg)" />
    <circle cx="310" cy="60" r="77" fill="var(--art-circle)" />
    <circle cx="64" cy="235" r="93" fill="var(--art-circle)" opacity=".55" />
    <path d="M37 194h325" stroke="var(--art-ink)" stroke-opacity=".18" />
    <g transform="rotate(-11 190 140)">
      <rect
        x="108"
        y="56"
        width="153"
        height="162"
        rx="10"
        fill="var(--art-ink)"
        opacity=".12"
        transform="translate(7 9)"
      />
      <rect x="108" y="56" width="153" height="162" rx="10" fill="var(--art-book)" />
      <path d="M129 57v160" stroke="var(--art-ink)" stroke-opacity=".18" />
      <rect x="148" y="87" width="81" height="64" rx="3" fill="var(--art-paper)" />
      <path
        d="M163 107h48m-48 12h34m-34 12h42"
        stroke="var(--art-ink)"
        stroke-width="2"
        stroke-linecap="round"
        stroke-opacity=".5"
      />
      <path
        d="M184 169h26m-26 9h26"
        stroke="var(--art-paper)"
        stroke-width="2"
        stroke-linecap="round"
      />
    </g>
    <path
      d="M290 188c-6-41 10-71 34-91"
      stroke="var(--art-ink)"
      stroke-width="3"
      stroke-linecap="round"
    />
    <path
      d="M295 159c-28-3-39-20-35-37 24-2 38 15 35 37Zm10-24c-6-22 3-40 24-43 8 23-1 41-24 43Zm-13 42c11-25 31-33 49-23-6 24-26 32-49 23Z"
      fill="var(--art-leaf)"
    />
    <path
      d="m64 69 6-13 6 13 13 6-13 6-6 13-6-13-13-6 13-6Z"
      fill="var(--art-leaf)"
      opacity=".55"
    />
    <circle cx="330" cy="211" r="3" fill="var(--art-ink)" opacity=".35" />
    <circle cx="77" cy="127" r="3" fill="var(--art-ink)" opacity=".35" />
  </svg>
</template>

<style scoped>
.blog-artwork {
  width: 100%;
  height: 100%;
}
.sage {
  --art-bg: #edf2e9;
  --art-circle: #dce7d6;
  --art-ink: #355c47;
  --art-book: #73927a;
  --art-paper: #fffaf0;
  --art-leaf: #608469;
}
.sand {
  --art-bg: #f2ede3;
  --art-circle: #e8dcc7;
  --art-ink: #735b39;
  --art-book: #ba9570;
  --art-paper: #fff9ed;
  --art-leaf: #928568;
}
.blue {
  --art-bg: #eaf0f2;
  --art-circle: #d8e4e8;
  --art-ink: #365768;
  --art-book: #7899a8;
  --art-paper: #f8faf7;
  --art-leaf: #6f9494;
}
</style>
