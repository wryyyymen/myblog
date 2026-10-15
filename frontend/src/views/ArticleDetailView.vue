<script setup lang="ts">
import ArticleDocument from '@/components/editor/ArticleDocument.vue'
import type { ContentFormat } from '@/utils/articleContent'
import AppIcon from '@/components/AppIcon.vue'
import { formatDate } from '@/utils/format'
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

interface Article {
  id: number
  authorId: number
  title: string
  content: string
  contentFormat?: ContentFormat
  createdAt: string
}

// 获取当前页面的路由信息
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const article = ref<Article | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const deleting = ref(false)
const deleteErrorMessage = ref('')

// 前端根据作者显示操作按钮，后端仍会独立检查权限
const canManage = computed(() => {
  if (article.value === null || auth.user === null) {
    return false
  }

  const isAdmin = auth.user.role === 'ADMIN'
  const isAuthor = auth.user.id === article.value.authorId

  return isAdmin || isAuthor
})

async function deleteArticle() {
  if (deleting.value || !article.value || !canManage.value) return
  if (!window.confirm('确定删除这篇文章吗？')) return

  deleting.value = true
  deleteErrorMessage.value = ''

  try {
    const response = await fetch(`/api/articles/${article.value.id}`, {
      method: 'DELETE',
    })

    if (!response.ok) {
      const result: { message?: string } = await response.json().catch(() => ({}))
      if (response.status === 401) auth.clearUser()
      deleteErrorMessage.value = result.message ?? `删除失败（${response.status}）`
      return
    }

    // 删除成功后，跳转回文章列表页
    await router.push('/')
  } catch {
    deleteErrorMessage.value = '删除未完成，请返回首页确认文章是否已删除'
  } finally {
    deleting.value = false
  }
}

async function loadArticle() {
  loading.value = true
  errorMessage.value = ''
  deleteErrorMessage.value = ''
  article.value = null

  try {
    // 从页面地址中取出文章 ID
    const id = encodeURIComponent(String(route.params.id))
    const response = await fetch(`/api/articles/${id}`)

    if (response.status === 404) {
      errorMessage.value = '文章不存在或已删除'
      return
    }

    if (!response.ok) {
      throw new Error(`请求失败：${response.status}`)
    }

    article.value = await response.json()
  } catch {
    errorMessage.value = '文章加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

// 首次进入页面，以及地址中的文章 ID 改变时，都加载文章
watch(() => route.params.id, loadArticle, { immediate: true })
</script>

<template>
  <main class="page-container reading-page">
    <RouterLink class="back-link" to="/"><AppIcon name="arrow-left" />返回文章列表</RouterLink>
    <div v-if="loading" class="surface empty-state" role="status">
      <AppIcon name="book" />
      <h2>正在打开文章</h2>
      <p>稍等片刻，开始阅读。</p>
    </div>
    <div v-else-if="errorMessage" class="surface empty-state" role="alert">
      <AppIcon name="book" />
      <h2>暂时无法阅读这篇文章</h2>
      <p>{{ errorMessage }}</p>
      <RouterLink class="button" to="/">返回首页</RouterLink>
    </div>
    <article v-else-if="article" class="surface reading-card">
      <header class="article-heading">
        <span class="eyebrow">NOTES & THOUGHTS</span>
        <h1>{{ article.title }}</h1>
        <div class="article-meta">
          <span
            ><AppIcon name="calendar" /><time :datetime="article.createdAt">{{
              formatDate(article.createdAt)
            }}</time></span
          >
          <span><AppIcon name="book" />一篇值得记录的文字</span>
        </div>
        <div v-if="canManage" class="article-actions">
          <RouterLink
            class="button button-soft button-small"
            :to="'/articles/' + article.id + '/edit'"
            ><AppIcon name="pen" />编辑文章</RouterLink
          >
          <button
            class="button button-danger button-small"
            type="button"
            @click="deleteArticle"
            :disabled="deleting"
          >
            {{ deleting ? '删除中……' : '删除文章' }}
          </button>
        </div>
        <p v-if="deleteErrorMessage" class="notice notice-error" role="alert">
          {{ deleteErrorMessage
          }}<RouterLink v-if="!auth.user" class="text-link" to="/login">去登录</RouterLink>
        </p>
      </header>
      <ArticleDocument :content="article.content" :content-format="article.contentFormat" />
      <footer class="reading-end">— 感谢阅读 —</footer>
    </article>
  </main>
</template>
