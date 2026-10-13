<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import BlogArtwork from '@/components/BlogArtwork.vue'
import { ref } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const username = ref('')
const password = ref('')
const message = ref('')
const loading = ref(false)
const isError = ref(false)
const router = useRouter()
const auth = useAuthStore()

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

    // 登录成功后回到首页
    await router.push({ name: 'home' })
  } catch {
    isError.value = true
    message.value = '无法连接后端，请确认后端已在 8081 端口启动'
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
        <p class="auth-description">登录你的账号，开始阅读与写作。</p>
        <form @submit.prevent="handleSubmit">
          <div class="field">
            <label for="username">用户名</label
            ><input
              id="username"
              v-model.trim="username"
              placeholder="输入你的用户名"
              autocomplete="username"
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
            还没有账号？<RouterLink to="/register">创建一个账号</RouterLink>
          </p>
        </form>
      </section>
    </div>
  </main>
</template>
