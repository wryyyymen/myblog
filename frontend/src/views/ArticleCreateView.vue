<script setup lang="ts">
import ArticleEditor from '@/components/editor/ArticleEditor.vue'
import { hasArticleText } from '@/utils/articleContent'
import AppIcon from '@/components/AppIcon.vue'
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

interface Category {
  id: number
  name: string
}

const router = useRouter()
const auth = useAuthStore()

const title = ref('')
const content = ref('')
const categoryId = ref<number | ''>('')
const categories = ref<Category[]>([])
const categoriesLoading = ref(true)
const categoriesError = ref('')

const submitting = ref(false)
const errorMessage = ref('')

async function loadCategories() {
  categoriesLoading.value = true
  categoriesError.value = ''
  categories.value = []
  categoryId.value = ''

  try {
    const response = await fetch('/api/categories')

    if (!response.ok) {
      throw new Error(`请求失败：${response.status}`)
    }

    const result: Category[] = await response.json()
    categories.value = result
  } catch {
    categoriesError.value = '获取分类列表失败，请稍后重试'
  } finally {
    categoriesLoading.value = false
  }
}

async function publishArticle() {
  if (submitting.value) return

  errorMessage.value = ''

  if (!auth.user) {
    errorMessage.value = '请先登录后发布文章'
    return
  }

  if (!title.value.trim() || !hasArticleText(content.value)) {
    errorMessage.value = '请输入标题和正文'
    return
  }

  const category = Number(categoryId.value)

  if (!Number.isSafeInteger(category) || category <= 0) {
    errorMessage.value = '请选择有效分类'
    return
  }

  if (!categories.value.some((item) => item.id === category)) {
    errorMessage.value = '请选择有效分类'
    return
  }

  submitting.value = true

  try {
    const response = await fetch('/api/articles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: title.value.trim(),
        content: content.value,
        contentFormat: 'HTML',
        categoryId: category,
      }),
    })

    const result: { id?: number; message?: string } = await response.json()

    if (!response.ok) {
      if (response.status === 401) auth.clearUser()
      errorMessage.value = result.message ?? `发布失败（${response.status}）`
      return
    }

    if (result.id == null) {
      errorMessage.value = '文章已发布，但未获取到文章编号，请返回首页查看'
      return
    }

    // 使用后端返回的新文章 ID，跳转到详情页
    await router.push({
      name: 'article-detail',
      params: { id: result.id },
    })
  } catch {
    errorMessage.value = '提交未完成，请检查网络，并在首页确认文章是否已发布'
  } finally {
    submitting.value = false
  }
}

onMounted(loadCategories)
</script>

<template>
  <main class="page-container editor-page">
    <RouterLink class="back-link" to="/"><AppIcon name="arrow-left" />返回文章列表</RouterLink>
    <header class="editor-heading">
      <span class="eyebrow">WRITE SOMETHING GOOD</span>
      <h1>写一篇新文章</h1>
      <p>把今天的发现写下来，让想法慢慢生长。</p>
    </header>
    <p v-if="errorMessage" class="notice notice-error" role="alert">{{ errorMessage }}</p>
    <div v-if="!auth.user" class="surface empty-state">
      <AppIcon name="pen" />
      <h2>登录后，开始你的记录</h2>
      <p>发布文章需要先登录账号。</p>
      <RouterLink class="button button-primary" to="/login"
        >去登录<AppIcon name="arrow-right"
      /></RouterLink>
    </div>
    <section v-else class="surface editor-card">
      <form class="editor-form" @submit.prevent="publishArticle">
        <div class="editor-author">
          <span class="avatar" aria-hidden="true">{{
            auth.user.username.slice(0, 1).toUpperCase()
          }}</span
          ><span
            >以 <strong>{{ auth.user.username }}</strong> 的身份发布</span
          >
        </div>
        <div class="field">
          <label for="title">文章标题</label>
          <input id="title" v-model="title" maxlength="200" placeholder="用一句话概括你的文章" :disabled="submitting" required />
          <div class="field-caption"><span>标题最多 200 个字符</span><span>{{ title.length }} / 200</span></div>
        </div>
        <div class="field">
          <label for="category">文章分类</label>
          <select
            id="category"
            v-model.number="categoryId"
            :disabled="categoriesLoading || submitting || categories.length === 0"
            required
          >
            <option value="">请选择分类</option>
            <option v-for="category in categories" :key="category.id" :value="category.id">
              {{ category.name }}
            </option>
          </select>
          <p v-if="categoriesLoading" class="muted" role="status">正在加载分类……</p>
          <p v-else-if="categoriesError" class="notice notice-error" role="alert">
            {{ categoriesError }}
          </p>
          <p v-else-if="categories.length === 0" class="muted">暂无可用分类</p>
          <button
            v-if="categoriesError"
            class="button button-small"
            type="button"
            @click="loadCategories"
          >
            重新加载分类
          </button>
        </div>
        <div class="field">
          <span class="field-label">正文</span>
          <ArticleEditor v-model="content" :disabled="submitting" />
        </div>
        <div class="editor-footer">
          <p>发布后，读者就能在博客中阅读你的文章。</p>
          <button
            class="button button-primary"
            type="submit"
            :disabled="submitting || categoriesLoading || categories.length === 0"
          >
            <AppIcon name="pen" />{{ submitting ? '发布中……' : '发布文章' }}
          </button>
        </div>
      </form>
    </section>
  </main>
</template>
