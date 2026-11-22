<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import BlogArtwork from '@/components/BlogArtwork.vue'
import { computed, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const username = ref('')
const password = ref('')
const message = ref('')
const loading = ref(false)
const isError = ref(false)
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const returningToGame = computed(
  () => typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/game'),
)

async function handleSubmit() {
  message.value = ''
  isError.value = false

  if (!username.value || !password.value) {
    message.value = '请输入用户名和密码'
    isError.value = true
    return
  }

  loading.value = true
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value, password: password.value }),
    })
    const result: {
      id?: number
      username?: string
      role?: string
      message?: string
    } = await response.json()

    if (!response.ok) {
      isError.value = true
      message.value = result.message ?? `登录失败（${response.status}）`
      return
    }

    if (
      result.id == null ||
      result.username == null ||
      (result.role !== 'USER' && result.role !== 'ADMIN')
    ) {
      isError.value = true
      message.value = '登录响应缺少有效的用户信息'
      return
    }

    // 保存用户信息，其他组件也能读取
    auth.setUser({
      id: result.id,
      username: result.username,
      role: result.role as 'USER' | 'ADMIN',
    })

    password.value = ''

    // 仅允许站内已定义路由，联机登录后回到原页面，避免外部地址跳转。
    const redirect = route.query.redirect
    if (
      typeof redirect === 'string' &&
      redirect.startsWith('/') &&
      !redirect.startsWith('//') &&
      !redirect.includes('\\') &&
      router.resolve(redirect).matched.length > 0
    ) {
      await router.push(redirect)
    } else {
      await router.push({ name: 'home' })
    }
  } catch {
    isError.value = true
    message.value = '暂时无法连接服务，请检查网络后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="page-container auth-page">
    <div class="surface auth-shell">
      <aside class="auth-story">
        <span class="eyebrow">WELCOME BACK</span>
        <h2>让每一次思考，<br />都有一个归处。</h2>
        <p>欢迎回来，继续你的记录，<br />也发现更多值得阅读的文字。</p>
        <div class="auth-art"><BlogArtwork :seed="0" /></div>
      </aside>
      <section class="auth-form-panel" aria-labelledby="login-title">
        <h1 id="login-title">登录博客</h1>
        <p class="auth-description">
          {{
            returningToGame
              ? '登录后返回联机大厅，继续与朋友合作。'
              : '登录你的账号，开始阅读与写作。'
          }}
        </p>
        <form @submit.prevent="handleSubmit">
          <div class="field">
            <label for="username">用户名</label
            ><input
              id="username"
              v-model.trim="username"
              placeholder="输入你的用户名"
              autocomplete="username"
              autocapitalize="none"
              :spellcheck="false"
              enterkeyhint="next"
              :disabled="loading"
              required
            />
          </div>
          <div class="field">
            <label for="password">密码</label
            ><input
              id="password"
              v-model="password"
              type="password"
              placeholder="输入你的密码"
              autocomplete="current-password"
              enterkeyhint="go"
              :disabled="loading"
              required
            />
          </div>
          <p
            v-if="message"
            class="notice"
            :class="isError ? 'notice-error' : 'notice-success'"
            :role="isError ? 'alert' : 'status'"
          >
            {{ message }}
          </p>
          <button class="button button-primary button-block" type="submit" :disabled="loading">
            {{ loading ? '验证中……' : '登录' }}<AppIcon name="arrow-right" />
          </button>
          <p class="auth-switch">
            还没有账号？<RouterLink
              :to="{ path: '/register', query: { redirect: route.query.redirect } }"
              >创建一个账号</RouterLink
            >
          </p>
        </form>
      </section>
    </div>
  </main>
</template>
