<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import BlogArtwork from '@/components/BlogArtwork.vue'
import { ref } from 'vue'
import { RouterLink } from 'vue-router'

const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const errorMessage = ref('')
const registered = ref(false)

async function handleRegister() {
  if (loading.value) return

  errorMessage.value = ''

  if (!username.value.trim()) {
    errorMessage.value = '用户名不能为空'
    return
  }

  if (!password.value.trim() || password.value.length < 8) {
    errorMessage.value = '密码不能为空，且至少需要 8 位'
    return
  }

  if (password.value !== confirmPassword.value) {
    errorMessage.value = '两次输入的密码不一致'
    return
  }

  loading.value = true

  try {
    const response = await fetch('/api/auth/register', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username: username.value.trim(),
        password: password.value,
      }),
    })

    const result: { message: string } = await response.json()
    if (!response.ok) {
      errorMessage.value = result.message || '注册失败，请稍后重试'
      return
    }

    // 清空输入框
    registered.value = true
    password.value = ''
    confirmPassword.value = ''
  } catch {
    errorMessage.value = '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="page-container auth-page">
    <div class="surface auth-shell">
      <aside class="auth-story">
        <span class="eyebrow">YOUR STORY STARTS HERE</span>
        <h2>从一段文字开始，<br />遇见更好的自己。</h2>
        <p>记录学习，分享灵感。<br />给你的每一个想法留一点空间。</p>
        <div class="auth-art"><BlogArtwork :seed="0" /></div>
      </aside>
      <section class="auth-form-panel" aria-labelledby="register-title">
        <template v-if="registered">
          <div class="auth-success" role="status">
            <AppIcon name="check" />
            <h1 id="register-title">注册成功</h1>
            <p>你的账号已经准备好了，<br />登录后就可以开始写第一篇文章。</p>
            <RouterLink class="button button-primary button-block" to="/login"
              >去登录<AppIcon name="arrow-right"
            /></RouterLink>
          </div>
        </template>
        <template v-else>
          <h1 id="register-title">创建账号</h1>
          <p class="auth-description">加入这里，开始你的记录。</p>
          <form @submit.prevent="handleRegister">
            <div class="field">
              <label for="register-username">用户名</label
              ><input
                id="register-username"
                v-model.trim="username"
                maxlength="50"
                placeholder="给自己取一个名字"
                autocomplete="username"
                :disabled="loading"
                required
              />
            </div>
            <div class="field">
              <label for="register-password">密码</label
              ><input
                id="register-password"
                v-model="password"
                type="password"
                minlength="8"
                placeholder="至少 8 位字符"
                autocomplete="new-password"
                :disabled="loading"
                required
              />
            </div>
            <div class="field">
              <label for="confirm-password">确认密码</label
              ><input
                id="confirm-password"
                v-model="confirmPassword"
                type="password"
                minlength="8"
                placeholder="再输入一次密码"
                autocomplete="new-password"
                :disabled="loading"
                required
              />
            </div>
            <p v-if="errorMessage" class="notice notice-error" role="alert">{{ errorMessage }}</p>
            <button class="button button-primary button-block" type="submit" :disabled="loading">
              {{ loading ? '注册中……' : '注册账号' }}<AppIcon name="arrow-right" />
            </button>
            <p class="auth-switch">已有账号？<RouterLink to="/login">去登录</RouterLink></p>
          </form>
        </template>
      </section>
    </div>
  </main>
</template>
