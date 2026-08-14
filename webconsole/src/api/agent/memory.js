import api from '@/api'

/**
 * 长期记忆 API：供 /chat/memory 页查看/编辑当前用户的长期记忆 Markdown 文件。
 * 用户写直接落盘，绕过异步 Memory Worker 的 LLM 判断。
 */
export const memoryApi = {
  // 列出当前用户 Workspace 全部 Markdown 文件相对路径（首次访问后端会懒生成模板）
  listFiles() {
    return api.get('/agent/memory/files')
  },

  // 读取指定相对路径的 Markdown 全文
  readFile(path) {
    return api.get('/agent/memory/file', { params: { path } })
  },

  // 整体覆盖写入指定相对路径的 Markdown
  writeFile(path, content) {
    return api.post('/agent/memory/file', { path, content })
  },

  // 一键重建核心模板（Agent.md / User.md / Directory.md），强制覆盖；Current/History 用户数据不动
  rebuildTemplates() {
    return api.post('/agent/memory/rebuild')
  }
}

export default memoryApi
