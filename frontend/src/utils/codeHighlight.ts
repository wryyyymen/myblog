import { createLowlight } from 'lowlight'
import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import bash from 'highlight.js/lib/languages/bash'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import plaintext from 'highlight.js/lib/languages/plaintext'
import { escapeHtml } from './articleContent'

const languages = { java, javascript, typescript, python, sql, bash, json, xml, css, plaintext }
export const lowlight = createLowlight(languages)
Object.entries(languages).forEach(([name, language]) => hljs.registerLanguage(name, language))

export const codeLanguages = [
  { value: 'plaintext', label: '纯文本' },
  { value: 'java', label: 'Java' },
  { value: 'javascript', label: 'JavaScript' },
  { value: 'typescript', label: 'TypeScript' },
  { value: 'python', label: 'Python' },
  { value: 'sql', label: 'SQL' },
  { value: 'bash', label: 'Shell' },
  { value: 'json', label: 'JSON' },
  { value: 'xml', label: 'HTML / XML' },
  { value: 'css', label: 'CSS' },
]

export function highlightCode(value: string, language: string): string {
  if (!Object.hasOwn(languages, language) || language === 'plaintext') return escapeHtml(value)
  return hljs.highlight(value, { language, ignoreIllegals: true }).value
}
