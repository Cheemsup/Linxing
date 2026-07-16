import { ref } from 'vue'
import MarkdownIt from 'markdown-it'

/**
 * Markdown 渲染 Hook
 * 将 Markdown 文本渲染为 HTML，支持代码高亮、表格、列表等常见语法。
 * 默认启用 HTML 转义以保证安全；外链统一在新标签页打开。
 */
const md = new MarkdownIt({
  html: false, // 禁止直接内嵌 HTML，避免 XSS
  linkify: true, // 自动识别纯文本链接
  breaks: true, // 单换行符渲染为 <br>，贴合聊天场景的行文习惯
  typographer: true
})

// 外链统一在新标签页打开，避免点击链接跳出当前应用
const defaultLinkOpen =
  md.renderer.rules.link_open ||
  function (tokens, idx, options, env, self) {
    return self.renderToken(tokens, idx, options)
  }
md.renderer.rules.link_open = function (tokens, idx, options, env, self) {
  const aIndex = tokens[idx].attrIndex('target')
  if (aIndex < 0) {
    tokens[idx].attrPush(['target', '_blank'])
    tokens[idx].attrPush(['rel', 'noopener noreferrer'])
  } else {
    tokens[idx].attrs[aIndex][1] = '_blank'
  }
  return defaultLinkOpen(tokens, idx, options, env, self)
}

export function useMarkdownRenderer() {
  const renderedHtml = ref('')

  const render = (markdown) => {
    if (!markdown) {
      renderedHtml.value = ''
      return
    }
    renderedHtml.value = md.render(markdown)
  }

  /**
   * 直接渲染为 HTML 字符串（不写入响应式状态），用于一次性场景。
   */
  const renderToHtml = (markdown) => {
    if (!markdown) return ''
    return md.render(markdown)
  }

  return {
    renderedHtml,
    render,
    renderToHtml
  }
}
