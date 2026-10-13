<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import { onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const logoutError = ref('')

async function handleLogout() {
  if (auth.loggingOut) return

  logoutError.value = ''

  try {
    await auth.logout()
  } catch {
    logoutError.value = '退出未完成，请检查网络后重试'
    return
  }

  await router.push({ name: 'home' })
}

// 根组件挂载时恢复登录状态
onMounted(() => {
  auth.restoreUser()
})
const year = new Date().getFullYear()
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">跳转到页面内容</a>
    <header class="site-header">
      <div class="header-inner">
        <RouterLink class="brand" to="/" aria-label="我的博客首页">
          <span class="brand-mark"><AppIcon name="book" /></span>
          <span
            ><span class="brand-name">我的博客</span
            ><span class="brand-caption">NOTES & THOUGHTS</span></span
          >
        </RouterLink>
        <nav class="main-nav" aria-label="主导航">
          <RouterLink to="/">文章</RouterLink>
          <RouterLink to="/about">关于</RouterLink>
        </nav>
        <div class="header-actions">
          <span v-if="auth.loading" class="header-status" role="status">正在加载登录状态……</span>
          <span v-else-if="auth.errorMessage" class="header-status" role="alert">{{
            auth.errorMessage
          }}</span>
          <template v-else-if="auth.user">
            <div class="user-menu">
              <span class="avatar" aria-hidden="true">{{
                auth.user.username.slice(0, 1).toUpperCase()
              }}</span>
              <span class="user-name">{{ auth.user.username }}</span>
              <button
                class="text-button"
                type="button"
                :disabled="auth.loggingOut"
                @click="handleLogout"
              >
                {{ auth.loggingOut ? '退出中……' : '退出' }}
              </button>
            </div>
            <RouterLink class="button button-primary" to="/articles/new"
              ><AppIcon name="pen" />写文章</RouterLink
            >
          </template>
          <template v-else>
            <RouterLink class="text-link" to="/login">登录</RouterLink>
            <RouterLink class="button button-soft" to="/register"
              >注册账号<AppIcon name="arrow-right"
            /></RouterLink>
          </template>
        </div>
      </div>
    </header>
    <p v-if="logoutError" class="notice notice-error logout-error" role="alert">
      {{ logoutError }}
    </p>
    <div id="main-content" class="page-body" tabindex="-1">
      <!-- 等登录状态查询结束，再显示当前页面。 -->
      <RouterView v-if="!auth.loading" />
      <div v-else class="boot-state" role="status">正在打开博客，请稍候……</div>
    </div>
    <footer class="site-footer">
      <span>© {{ year }} 我的博客 · 记录每一次思考</span>
      <div class="footer-links">
        <RouterLink to="/">阅读文章</RouterLink><RouterLink to="/about">关于这里</RouterLink>
      </div>
    </footer>
  </div>
</template>
