import DOMPurify from 'dompurify'

export type ContentFormat = 'TEXT' | 'HTML'

// 清理 HTML，只保留文章需要的标签和属性
export function sanitizeArticleHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      'p',
      'br',
      'h2',
      'h3',
      'h4',
      'strong',
      'em',
      's',
      'ul',
      'ol',
      'li',
      'blockquote',
      'pre',
      'code',
      'hr',
    ],
    ALLOWED_ATTR: ['class', 'start'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
  })
}

// 判断富文本正文是否包含实际文字
export function hasArticleText(html: string): boolean {
  const document = new DOMParser().parseFromString(sanitizeArticleHtml(html), 'text/html')

  const text = document.body.textContent ?? ''

  return text.replace(/[\u00a0\u200b]/g, ' ').trim().length > 0
}

export function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

// 没有格式字段的旧文章按纯文本处理，避免把原文里的标签解释为 HTML。
export function toEditorHtml(value: string, format: ContentFormat = 'TEXT'): string {
  if (format === 'HTML') return sanitizeArticleHtml(value)
  return `<p>${escapeHtml(value).replace(/\r\n?/g, '\n').replace(/\n/g, '<br>')}</p>`
}

export function articleText(value: string, format: ContentFormat = 'TEXT'): string {
  if (format !== 'HTML') return value
  const document = new DOMParser().parseFromString(sanitizeArticleHtml(value), 'text/html')
  document.querySelectorAll('br').forEach((node) => node.replaceWith('\n'))
  document.querySelectorAll('p,h2,h3,h4,li,blockquote,pre').forEach((node) => node.append('\n'))
  return document.body.textContent ?? ''
}
