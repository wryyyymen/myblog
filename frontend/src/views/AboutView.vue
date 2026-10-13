<script setup lang="ts">
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
</script>

<template>
  <main class="page-container about-page">
    <RouterLink class="back-link" to="/"><AppIcon name="arrow-left" />返回文章列表</RouterLink>
    <section class="surface about-card">
      <span class="eyebrow">ABOUT THIS LITTLE SPACE</span>
      <h1>记录学习，分享想法。</h1>
      <p class="about-intro">
        这里是一个放下思考、记录成长的地方。无论是学习中的一次发现，还是日常里的一点灵感，都可以成为一篇文章的开始。
      </p>
      <div class="about-grid">
        <section>
          <AppIcon name="book" />
          <h2>认真阅读</h2>
          <p>在文字里发现新的视角，让好奇心带你走得更远。</p>
        </section>
        <section>
          <AppIcon name="pen" />
          <h2>持续记录</h2>
          <p>写下今天学会的事，也留下探索过程中的点点滴滴。</p>
        </section>
        <section>
          <AppIcon name="user" />
          <h2>分享发现</h2>
          <p>让自己的经验成为别人的一点帮助，让想法被看见。</p>
        </section>
      </div>
      <RouterLink class="button button-primary" to="/"
        >去读一篇文章<AppIcon name="arrow-right"
      /></RouterLink>
    </section>
  </main>
</template>
