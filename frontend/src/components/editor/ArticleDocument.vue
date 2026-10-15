<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { sanitizeArticleHtml, type ContentFormat } from '@/utils/articleContent'
import { codeLanguages, highlightCode } from '@/utils/codeHighlight'
import '@/assets/document.css'

const props = defineProps<{ content: string; contentFormat?: ContentFormat }>()
const copyMessage = ref('')
watch(() => props.content, () => { copyMessage.value = '' })

const documentView = computed(() => {
  const document = new DOMParser().parseFromString(sanitizeArticleHtml(props.content), 'text/html')
  // 清除来稿的 CSS 类；高亮、目录和复制按钮由页面自己生成。
  document.querySelectorAll('[class]').forEach((node) => {
    if (node.tagName !== 'CODE') node.removeAttribute('class')
  })
  const headings = Array.from(document.querySelectorAll('h2,h3,h4')).map((node, index) => {
    node.id = `article-section-${index}`
    return { id: node.id, text: node.textContent ?? '', level: Number(node.tagName.slice(1)) }
  })
  document.querySelectorAll('pre > code').forEach((code) => {
    const language = codeLanguages.find((item) => code.classList.contains(`language-${item.value}`))
    code.className = 'hljs'
    code.innerHTML = highlightCode(code.textContent ?? '', language?.value ?? 'plaintext')
    const pre = code.parentElement!
    const wrapper = document.createElement('div')
    wrapper.className = 'document-code-block'
    const bar = document.createElement('div')
    bar.className = 'document-code-bar'
    const label = document.createElement('span')
    label.textContent = language?.label ?? '纯文本'
    const button = document.createElement('button')
    button.type = 'button'
    button.dataset.copyCode = 'true'
    button.textContent = '复制代码'
    bar.append(label, button)
    pre.replaceWith(wrapper)
    wrapper.append(bar, pre)
  })
  return { html: document.body.innerHTML, headings }
})

async function copyCode(event: MouseEvent) {
  if (!(event.target instanceof Element)) return
  const button = event.target.closest('button[data-copy-code]')
  if (!button) return
  const code = button.closest('.document-code-block')?.querySelector('pre code')
  if (!code) return
  try {
    await navigator.clipboard.writeText(code.textContent ?? '')
    copyMessage.value = '代码已复制'
  } catch {
    copyMessage.value = '复制失败，请选中代码手动复制'
  }
}
</script>

<template>
  <div v-if="contentFormat === 'HTML'" class="article-document">
    <nav v-if="documentView.headings.length" class="document-outline" aria-label="文章目录">
      <strong>文章目录</strong>
      <a v-for="heading in documentView.headings" :key="heading.id" :href="`#${heading.id}`" :style="{ paddingLeft: `${(heading.level - 2) * 14}px` }">{{ heading.text }}</a>
    </nav>
    <!-- 仅渲染经过白名单清理的正文，以及本组件生成的高亮和按钮。 -->
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div class="document-content document-reader" @click="copyCode" v-html="documentView.html" />
    <p class="document-copy-status" role="status">{{ copyMessage }}</p>
  </div>
  <div v-else class="article-content">{{ content }}</div>
</template>
