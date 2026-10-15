<script setup lang="ts">
import ArticleEditor from '@/components/editor/ArticleEditor.vue'
import { hasArticleText, toEditorHtml, type ContentFormat } from '@/utils/articleContent'
import AppIcon from '@/components/AppIcon.vue'
// setup：这里声明的变量和函数可以直接在下面的模板中使用
// lang="ts"：这一部分使用 TypeScript

// ref：创建响应式数据
// watch：监听数据变化，并执行指定函数
import { computed, ref, watch } from 'vue'

// RouterLink：用于页面内的路由跳转
// useRoute：读取当前页面的路由信息
// useRouter：获取可以控制页面跳转的路由对象
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// 定义文章对象应当具备的字段和类型
// 这是 TypeScript 的类型说明，不是创建一篇文章
interface Article {
  id: number
  authorId: number
  title: string
  content: string
  contentFormat?: ContentFormat
}

// 当前路由信息，例如网址中的文章 id
const route = useRoute()

// 路由控制对象，例如让页面跳转到文章详情页
const router = useRouter()
const auth = useAuthStore()

// 保存正在编辑的文章编号
// number | null：允许存放数字，也允许存放 null
// 初始为 null，表示目前还没有加载到文章
const articleId = ref<number | null>(null)
const authorId = ref<number | null>(null)
const canEdit = computed(() => {
  if (auth.user === null || authorId.value === null) {
    return false
  }

  const isAdmin = auth.user.role === 'ADMIN'
  const isAuthor = auth.user.id === authorId.value

  return isAdmin || isAuthor
})

// 表单中的标题和正文，初始为空字符串
const title = ref('')
const content = ref('')

// 是否正在加载文章
const loading = ref(true)

// 是否正在保存修改
const saving = ref(false)

// 加载文章时的错误信息
// 空字符串表示目前没有错误信息
const loadError = ref('')

// 保存文章时的错误信息
const saveError = ref('')

// 加载原文章，并把文章内容填入表单
// async：这个函数里面需要等待异步操作
async function loadArticle() {
  // 开始加载，页面显示“正在加载文章……”
  loading.value = true

  // 清除之前的错误提示
  loadError.value = ''
  saveError.value = ''

  // 清除上一次加载的文章，避免显示旧内容
  articleId.value = null
  authorId.value = null
  title.value = ''
  content.value = ''

  try {
    // route.params.id：获取路由中的 id 参数
    // String(...)：把它转换成字符串
    // encodeURIComponent(...)：编码成适合放入 URL 的形式
    const id = encodeURIComponent(String(route.params.id))

    // 请求后端文章详情接口
    // 没有指定 method 时，fetch 默认使用 GET
    // 例如 id 为 3，这里请求 /api/articles/3
    const response = await fetch(`/api/articles/${id}`)

    // 404：后端找不到这篇文章
    if (response.status === 404) {
      loadError.value = '文章不存在或已删除'

      // 停止继续处理
      // 注意：即使这里 return，下面的 finally 仍然会执行
      return
    }

    // response.ok：状态码在 200～299 时为 true
    // 其他失败状态在这里主动抛出异常，交给 catch 处理
    if (!response.ok) {
      throw new Error('加载失败')
    }

    // 读取响应体，并把 JSON 解析成 JavaScript 对象
    // : Article 表示我们预期得到的是 Article 类型的数据
    const article: Article = await response.json()

    // 把后端返回的文章信息放入响应式变量
    // 因为输入框使用了 v-model，所以表单会显示这些内容
    articleId.value = article.id
    authorId.value = article.authorId
    title.value = article.title
    content.value = toEditorHtml(article.content, article.contentFormat)
  } catch {
    // 请求出错、JSON 解析失败，或者上面主动抛出异常时进入这里
    loadError.value = '文章加载失败，请稍后重试'
  } finally {
    // 无论成功、失败，还是在 try 中提前 return，
    // 都结束“正在加载”的状态
    loading.value = false
  }
}

// 提交用户修改后的文章
async function saveArticle() {
  // 以下任意条件成立，就停止保存：
  // 1. 已经正在保存，避免重复提交
  // 2. 文章还在加载
  // 3. 尚未获得有效的文章编号
  if (saving.value || loading.value || articleId.value === null) return

  // 清除之前的保存错误提示
  saveError.value = ''

  if (!canEdit.value) {
    saveError.value = '没有编辑这篇文章的权限'
    return
  }

  // 标题检查空白，富文本正文先提取文字，避免把 <p></p> 当成有效正文
  if (!title.value.trim() || !hasArticleText(content.value)) {
    saveError.value = '请输入标题和正文'
    return
  }

  // 开始保存
  // 页面会禁用输入框和按钮，并显示“保存中……”
  saving.value = true

  try {
    // 请求修改当前文章的接口
    const response = await fetch(`/api/articles/${articleId.value}`, {
      // PUT：这里用于修改已有文章
      method: 'PUT',

      // 告诉后端：请求体使用 JSON 格式
      headers: { 'Content-Type': 'application/json' },

      // 把 JavaScript 对象转换成 JSON 字符串，作为请求体
      body: JSON.stringify({
        // 提交标题时，去掉两端的空白
        title: title.value.trim(),

        // 编辑器输出 HTML，格式字段用于区分旧的纯文本文章
        content: content.value,
        contentFormat: 'HTML',
      }),
    })

    // 后端返回了失败状态
    if (!response.ok) {
      // 尝试读取后端返回的错误信息
      // message?：这个字段允许不存在
      //
      // 如果响应体不是有效 JSON，解析会失败
      // .catch(() => ({}))：此时使用空对象作为备用结果
      const result: { message?: string } = await response.json().catch(() => ({}))
      if (response.status === 401) auth.clearUser()

      // 优先显示后端提供的 message
      // 如果 message 是 null 或 undefined，
      // 就显示备用提示，例如“保存失败（403）”
      saveError.value = result.message ?? `保存失败（${response.status}）`

      // 保存失败，不跳转页面
      return
    }

    // 保存成功，跳转到文章详情页
    // article-detail 是路由配置中的 name
    // params 把当前文章编号传给这个路由
    await router.push({
      name: 'article-detail',
      params: { id: articleId.value },
    })
  } catch {
    // 请求或后续操作出现异常
    // 此时后端可能已经保存成功，所以提示用户去详情页确认
    saveError.value = '操作未完成，请返回详情页确认修改结果'
  } finally {
    // 本次保存流程结束
    saving.value = false
  }
}

// 监听路由中的文章 id
//
// 第一个参数：一个函数，用来读取需要监听的值
// 第二个参数：id 改变时执行 loadArticle
// 第三个参数：immediate: true 表示创建监听时就执行一次
//
// 因此：
// 首次打开编辑页，会加载文章
// 后续切换到另一篇文章的编辑页，也会重新加载
watch(() => route.params.id, loadArticle, { immediate: true })
</script>

<template>
  <main class="page-container editor-page">
    <RouterLink class="back-link" to="/"><AppIcon name="arrow-left" />返回文章列表</RouterLink>
    <header class="editor-heading">
      <span class="eyebrow">A LITTLE MORE POLISHED</span>
      <h1>编辑文章</h1>
      <p>再打磨一下，让你的表达更清晰。</p>
    </header>
    <div v-if="loading" class="surface empty-state" role="status">
      <AppIcon name="book" />
      <h2>正在加载文章</h2>
      <p>原来的内容马上就会出现在这里。</p>
    </div>
    <div v-else-if="loadError" class="surface empty-state" role="alert">
      <AppIcon name="book" />
      <h2>文章暂时无法打开</h2>
      <p>{{ loadError }}</p>
      <RouterLink class="button" to="/">返回首页</RouterLink>
    </div>
    <div v-else-if="!auth.user" class="surface empty-state">
      <AppIcon name="pen" />
      <h2>请先登录后编辑文章</h2>
      <p>管理员或文章作者本人可以编辑这篇文章。</p>
      <RouterLink class="button button-primary" to="/login"
        >去登录<AppIcon name="arrow-right"
      /></RouterLink>
    </div>
    <div v-else-if="!canEdit" class="surface empty-state" role="alert">
      <AppIcon name="user" />
      <h2>没有编辑这篇文章的权限</h2>
      <p>你可以阅读文章，或返回首页浏览其他内容。</p>
      <RouterLink class="button" to="/">返回首页</RouterLink>
    </div>
    <section v-else class="surface editor-card">
      <!-- 保留原有 v-model 和提交方法，样式与发布页共用。 -->
      <form class="editor-form" @submit.prevent="saveArticle">
        <div class="editor-author">
          <span class="avatar" aria-hidden="true">{{
            auth.user.username.slice(0, 1).toUpperCase()
          }}</span
          ><span
            >正在编辑文章 <strong>#{{ articleId }}</strong></span
          >
        </div>
        <div class="field">
          <label for="title">文章标题</label
          ><input
            id="title"
            v-model="title"
            maxlength="200"
            placeholder="用一句话概括你的文章"
            :disabled="saving"
            required
          />
          <div class="field-caption">
            <span>标题最多 200 个字符</span><span>{{ title.length }} / 200</span>
          </div>
        </div>
        <div class="field">
          <span class="field-label">正文</span>
          <ArticleEditor v-model="content" :disabled="saving" />
        </div>
        <p v-if="saveError" class="notice notice-error" role="alert">{{ saveError }}</p>
        <div class="editor-footer">
          <p>保存后，修改将立即显示在文章中。</p>
          <button class="button button-primary" type="submit" :disabled="saving">
            <AppIcon name="check" />{{ saving ? '保存中……' : '保存修改' }}
          </button>
        </div>
      </form>
    </section>
  </main>
</template>
