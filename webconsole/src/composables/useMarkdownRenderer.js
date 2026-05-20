import { ref } from 'vue'

/**
 * Markdown 渲染 Hook
 * 用于将 Markdown 文本渲染为 HTML
 */
export function useMarkdownRenderer() {
  const renderedHtml = ref('')

  const render = (markdown) => {
    // TODO: 接入 markdown-it 或 marked 等渲染库
    renderedHtml.value = markdown || ''
  }

  return {
    renderedHtml,
    render
  }
}
