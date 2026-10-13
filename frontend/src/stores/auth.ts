import { ref } from 'vue'
import { defineStore } from 'pinia'

interface User {
  id: number
  username: string
  role: 'USER' | 'ADMIN'
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const loading = ref(true)
  const errorMessage = ref('')
  const loggingOut = ref(false)

  function clearUser() {
    user.value = null
    errorMessage.value = ''
  }

  // 登录成功后保存用户信息
  function setUser(loginUser: User) {
    user.value = loginUser
    errorMessage.value = ''
  }

  // 网站启动时，向后端查询当前用户
  async function restoreUser() {
    loading.value = true
    errorMessage.value = ''

    try {
      const response = await fetch('/api/auth/me')

      // 未登录是正常状态
      if (response.status === 401) {
        clearUser()
        return
      }

      if (!response.ok) {
        throw new Error('查询登录状态失败')
      }

      const result: User = await response.json()
      user.value = result
    } catch {
      errorMessage.value = '登录状态暂时无法加载，请刷新重试'
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    if (loggingOut.value) return

    loggingOut.value = true

    try {
      const response = await fetch('/api/auth/logout', { method: 'POST' })

      if (!response.ok) {
        throw new Error('退出登录失败')
      }

      clearUser()
    } finally {
      loggingOut.value = false
    }
  }

  return { user, loading, errorMessage, loggingOut, setUser, clearUser, restoreUser, logout }
})
