<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight'
import Placeholder from '@tiptap/extension-placeholder'
import { sanitizeArticleHtml } from '@/utils/articleContent'
import { codeLanguages, lowlight } from '@/utils/codeHighlight'
import '@/assets/document.css'

const props = withDefaults(defineProps<{ modelValue: string; disabled?: boolean }>(), {
  disabled: false,
})
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const revision = ref(0)
let lastEmitted = props.modelValue

const editor = useEditor({
  extensions: [
    StarterKit.configure({ heading: { levels: [2, 3, 4] }, codeBlock: false, link: false, underline: false }),
    CodeBlockLowlight.configure({ lowlight, defaultLanguage: 'plaintext', enableTabIndentation: true, tabSize: 2 }),
    Placeholder.configure({ placeholder: '写下第一句话，或通过上方工具栏插入标题、列表和代码块……' }),
  ],
  content: sanitizeArticleHtml(props.modelValue),
  editable: !props.disabled,
  editorProps: {
    attributes: { class: 'document-content', role: 'textbox', 'aria-label': '文章正文', 'aria-multiline': 'true' },
  },
  onUpdate({ editor }) {
    // v-model：把编辑器生成的 HTML 传回发布页或编辑页。
    lastEmitted = editor.isEmpty ? '' : editor.getHTML()
    emit('update:modelValue', lastEmitted)
  },
  onTransaction() { revision.value++ },
})

watch(() => props.modelValue, (value) => {
  const instance = editor.value
  if (!instance || value === lastEmitted || value === instance.getHTML() || (!value && instance.isEmpty)) return
  // 回填时不再触发 update，避免父子组件反复同步。
  lastEmitted = value
  instance.commands.setContent(sanitizeArticleHtml(value), { emitUpdate: false })
})
watch(() => props.disabled, (value) => editor.value?.setEditable(!value))

const state = computed(() => {
  void revision.value
  const instance = editor.value
  return {
    heading: [2, 3, 4].find((level) => instance?.isActive('heading', { level })) ?? 0,
    code: instance?.isActive('codeBlock') ?? false,
    language: instance?.getAttributes('codeBlock').language ?? 'plaintext',
    characters: instance?.getText().replace(/\s/g, '').length ?? 0,
    canUndo: instance?.can().undo() ?? false,
    canRedo: instance?.can().redo() ?? false,
  }
})

function changeHeading(event: Event) {
  const level = Number((event.target as HTMLSelectElement).value) as 0 | 2 | 3 | 4
  if (level === 0) editor.value?.chain().focus().setParagraph().run()
  else editor.value?.chain().focus().setHeading({ level }).run()
}

function changeLanguage(event: Event) {
  editor.value?.chain().focus().updateAttributes('codeBlock', {
    language: (event.target as HTMLSelectElement).value,
  }).run()
}

function continueParagraph() {
  // 代码块常带有一个尾随空段落。明确把光标移到末尾，避免继续输入时仍落在代码里。
  const instance = editor.value
  if (!instance) return
  if (instance.state.doc.lastChild?.type.name === 'codeBlock') instance.commands.exitCode()
  instance.commands.setTextSelection(instance.state.doc.content.size - 1)
  instance.view.focus()
}
</script>

<template>
  <div class="document-editor" :class="{ 'is-disabled': disabled }">
    <div v-if="editor" class="document-toolbar" role="group" aria-label="正文排版工具">
      <select aria-label="段落样式" :value="state.heading" :disabled="disabled || state.code" @change="changeHeading">
        <option :value="0">正文</option>
        <option :value="2">一级标题</option>
        <option :value="3">二级标题</option>
        <option :value="4">三级标题</option>
      </select>
      <button type="button" :disabled="disabled || state.code" :aria-pressed="editor.isActive('bold')" @click="editor.chain().focus().toggleBold().run()">加粗</button>
      <button type="button" :disabled="disabled || state.code" :aria-pressed="editor.isActive('italic')" @click="editor.chain().focus().toggleItalic().run()">斜体</button>
      <button type="button" :disabled="disabled || state.code" :aria-pressed="editor.isActive('strike')" @click="editor.chain().focus().toggleStrike().run()">删除线</button>
      <button type="button" :disabled="disabled || state.code" :aria-pressed="editor.isActive('code')" @click="editor.chain().focus().toggleCode().run()">行内代码</button>
      <button type="button" :disabled="disabled || state.code" :aria-pressed="editor.isActive('bulletList')" @click="editor.chain().focus().toggleBulletList().run()">无序列表</button>
      <button type="button" :disabled="disabled || state.code" :aria-pressed="editor.isActive('orderedList')" @click="editor.chain().focus().toggleOrderedList().run()">有序列表</button>
      <button type="button" :disabled="disabled || state.code" :aria-pressed="editor.isActive('blockquote')" @click="editor.chain().focus().toggleBlockquote().run()">引用</button>
      <button type="button" :disabled="disabled" :aria-pressed="state.code" @click="editor.chain().focus().toggleCodeBlock().run()">代码块</button>
      <button type="button" :disabled="disabled || state.code" @click="editor.chain().focus().setHorizontalRule().run()">分隔线</button>
      <button type="button" :disabled="disabled || !state.canUndo" @click="editor.chain().focus().undo().run()">撤销</button>
      <button type="button" :disabled="disabled || !state.canRedo" @click="editor.chain().focus().redo().run()">重做</button>
    </div>
    <div v-if="editor && state.code" class="document-code-tools">
      <label>代码语言
        <select aria-label="代码语言" :value="state.language" :disabled="disabled" @change="changeLanguage">
          <option v-for="language in codeLanguages" :key="language.value" :value="language.value">{{ language.label }}</option>
        </select>
      </label>
      <button type="button" :disabled="disabled" @click="continueParagraph">继续写正文</button>
      <span>Tab 缩进 · Ctrl / ⌘ + Enter 退出代码块</span>
    </div>
    <EditorContent :editor="editor" />
    <div class="document-status">
      <span>{{ state.characters }} 字符</span>
      <span>支持标题、列表和代码块 · 修改后请点击发布或保存</span>
    </div>
  </div>
</template>
