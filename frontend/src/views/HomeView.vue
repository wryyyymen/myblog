<script setup lang="ts">
import type { ContentFormat } from '@/utils/articleContent'
import AppIcon from '@/components/AppIcon.vue'
import BlogArtwork from '@/components/BlogArtwork.vue'
import { articleExcerpt, formatDate } from '@/utils/format'
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// 定义当前页面需要使用的文章字段
interface Article {
  id: number
  title: string
  createdAt: string
  content?: string
  contentFormat?: ContentFormat
  categoryId?: number
  coverUrl?: string | null
}

interface Category {
  id: number
  name: string
}

// 分页接口返回一个对象，文章数组放在 content 中
interface ArticlePage {
  content: Article[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

const articles = ref<Article[]>([])
const categories = ref<Category[]>([])
const selectedCategoryId = ref<number | ''>('')
// 后端页码从 0 开始，页面显示时再加 1
const currentPage = ref(0)
const pageSize = 5
const totalElements = ref(0)
const totalPages = ref(0)
const loading = ref(true)
const errorMessage = ref('')
const categoriesLoading = ref(true)
const categoriesError = ref('')

async function loadCategories() {
  categoriesLoading.value = true
  categoriesError.value = ''

  try {
    const response = await fetch('/api/categories')

    if (!response.ok) {
      throw new Error('分类加载失败')
    }

    categories.value = await response.json()
  } catch {
    categoriesError.value = '分类加载失败，请刷新页面重试'
  } finally {
    categoriesLoading.value = false
  }
}

// 请求指定页的文章；不传页码时加载第一页
async function loadArticles(page = 0) {
  loading.value = true
  errorMessage.value = ''

  try {
    const params = new URLSearchParams({
      page: String(page),
      size: String(pageSize),
    })

    if (selectedCategoryId.value !== '') {
      params.set('categoryId', String(selectedCategoryId.value))
    }

    const response = await fetch(`/api/articles/page?${params.toString()}`)

    if (!response.ok) {
      throw new Error(`请求失败：${response.status}`)
    }

    const result: ArticlePage = await response.json()
    articles.value = result.content
    currentPage.value = result.page
    totalElements.value = result.totalElements
    totalPages.value = result.totalPages
  } catch {
    errorMessage.value = '文章加载失败，请刷新页面重试'
  } finally {
    loading.value = false
  }
}

// 页面挂载后加载文章
onMounted(() => {
  loadCategories()
  loadArticles()
})

const failedCovers = ref(new Set<number>())

function categoryName(id?: number) {
  return categories.value.find((category) => category.id === id)?.name ?? '文章'
}

async function filterByCategory(id: number | '') {
  if (loading.value) return
  selectedCategoryId.value = id
  await loadArticles(0)
}
</script>

<template>
  <main class="page-container blog-page">
    <section class="surface home-hero" aria-labelledby="welcome-title">
      <div class="hero-copy">
        <span class="eyebrow">A LITTLE SPACE FOR BIG IDEAS</span>
        <h1 id="welcome-title">记录日常，<br />也记录成长。</h1>
        <p>把学习中的发现、生活里的灵感，慢慢写成属于自己的文字。</p>
        <a class="button button-primary" href="#articles">开始阅读<AppIcon name="arrow-right" /></a>
      </div>
      <div class="hero-art"><BlogArtwork :seed="0" /></div>
    </section>
    <div class="home-layout">
      <section id="articles" class="article-section" aria-labelledby="articles-title">
        <div class="section-heading">
          <div>
            <h2 id="articles-title">最新文章</h2>
            <p class="section-caption">
              {{ loading ? '正在整理文章……' : '共 ' + totalElements + ' 篇文章，慢慢读。' }}
            </p>
          </div>
          <div class="category-filter">
            <AppIcon name="grid" />
            <select
              id="category-filter"
              v-model="selectedCategoryId"
              aria-label="按分类筛选文章"
              :disabled="categoriesLoading || loading"
              @change="loadArticles(0)"
            >
              <option value="">全部分类</option>
              <option v-for="category in categories" :key="category.id" :value="category.id">
                {{ category.name }}
              </option>
            </select>
          </div>
        </div>
        <p v-if="categoriesError" class="notice notice-error" role="alert">{{ categoriesError }}</p>
        <div v-if="loading" class="surface empty-state" role="status">
          <AppIcon name="book" />
          <h3>正在加载文章</h3>
          <p>精彩内容即将呈现。</p>
        </div>
        <div v-else-if="errorMessage" class="surface empty-state" role="alert">
          <AppIcon name="book" />
          <h3>文章暂时没有加载出来</h3>
          <p>{{ errorMessage }}</p>
          <button class="button" type="button" @click="loadArticles(currentPage)">重新加载</button>
        </div>
        <div v-else-if="articles.length === 0" class="surface empty-state">
          <AppIcon name="pen" />
          <h3>这里还没有文章</h3>
          <p>换个分类看看，或者写下第一篇记录。</p>
          <RouterLink class="button button-primary" :to="auth.user ? '/articles/new' : '/login'">{{
            auth.user ? '发布文章' : '登录后发布文章'
          }}</RouterLink>
        </div>
        <div v-else class="article-list">
          <article v-for="article in articles" :key="article.id" class="surface article-card">
            <RouterLink
              class="article-cover"
              :to="'/articles/' + article.id"
              :aria-label="'阅读文章：' + article.title"
            >
              <img
                v-if="article.coverUrl && !failedCovers.has(article.id)"
                :src="article.coverUrl"
                alt=""
                loading="lazy"
                @error="failedCovers.add(article.id)"
              />
              <BlogArtwork v-else :seed="article.id" />
            </RouterLink>
            <div class="article-card-body">
              <span class="badge">{{ categoryName(article.categoryId) }}</span>
              <h3>
                <RouterLink :to="'/articles/' + article.id">{{ article.title }}</RouterLink>
              </h3>
              <p class="article-excerpt">{{ articleExcerpt(article.content, article.contentFormat) }}</p>
              <div class="article-card-footer">
                <time class="article-date" :datetime="article.createdAt"
                  ><AppIcon name="calendar" />{{ formatDate(article.createdAt) }}</time
                >
                <RouterLink class="text-link read-link" :to="'/articles/' + article.id"
                  >阅读全文<AppIcon name="arrow-right"
                /></RouterLink>
              </div>
            </div>
          </article>
        </div>
        <nav v-if="!errorMessage && totalPages > 0" class="pagination" aria-label="文章分页">
          <button
            class="button"
            type="button"
            :disabled="loading || currentPage === 0"
            @click="loadArticles(currentPage - 1)"
          >
            <AppIcon name="arrow-left" />上一页
          </button>
          <span aria-live="polite">第 {{ currentPage + 1 }} / {{ totalPages }} 页</span>
          <button
            class="button"
            type="button"
            :disabled="loading || currentPage >= totalPages - 1"
            @click="loadArticles(currentPage + 1)"
          >
            下一页<AppIcon name="arrow-right" />
          </button>
        </nav>
      </section>
      <aside class="sidebar" aria-label="博客侧栏">
        <section class="surface sidebar-card profile-card">
          <div class="avatar avatar-large" aria-hidden="true">
            {{ auth.user ? auth.user.username.slice(0, 1).toUpperCase() : '记' }}
          </div>
          <h2>{{ auth.user ? '你好，' + auth.user.username : '欢迎来到这里' }}</h2>
          <p>每一段文字，都值得被认真记录。<br />写下你的想法，分享你的发现。</p>
          <RouterLink
            class="button button-primary button-block"
            :to="auth.user ? '/articles/new' : '/login'"
            ><AppIcon name="pen" />{{ auth.user ? '写一篇新文章' : '登录，开始记录' }}</RouterLink
          >
          <div class="sidebar-label">保持好奇，持续表达。</div>
        </section>
        <section class="surface sidebar-card">
          <h2>分类浏览</h2>
          <p v-if="categoriesLoading" class="muted" role="status">正在加载分类……</p>
          <p v-else-if="categoriesError" class="muted">分类暂时不可用</p>
          <div v-else class="category-list">
            <button
              class="category-item"
              :class="{ active: selectedCategoryId === '' }"
              type="button"
              :disabled="loading"
              :aria-pressed="selectedCategoryId === ''"
              @click="filterByCategory('')"
            >
              <AppIcon name="grid" />全部文章
            </button>
            <button
              v-for="category in categories"
              :key="category.id"
              class="category-item"
              :class="{ active: Number(selectedCategoryId) === category.id }"
              type="button"
              :disabled="loading"
              :aria-pressed="Number(selectedCategoryId) === category.id"
              @click="filterByCategory(category.id)"
            >
              <AppIcon name="tag" />{{ category.name }}
            </button>
          </div>
        </section>
        <section class="sidebar-note">
          <span class="eyebrow">A SMALL REMINDER</span>
          <p>不必等到想法完美才动笔。<br />从今天的一点小发现开始。</p>
        </section>
      </aside>
    </div>
  </main>
</template>
