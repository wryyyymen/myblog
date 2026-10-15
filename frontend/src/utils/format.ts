import { articleText, type ContentFormat } from './articleContent'

// 统一把后端日期显示成适合阅读的中文日期。
export function formatDate(value?: string): string {
  if (!value) return '日期待更新'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '日期待更新'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}

// 列表只展示正文开头；正文仍在详情页完整展示。
export function articleExcerpt(content?: string, format: ContentFormat = 'TEXT'): string {
  const text = articleText(content ?? '', format).replace(/\s+/g, ' ').trim()
  return text ? text.slice(0, 120) : '打开这篇文章，继续阅读完整内容。'
}
