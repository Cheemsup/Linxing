import { reactive } from 'vue'
import { chatSessionApi } from '@/api/agent/chat'

/**
 * 会话列表共享状态
 *
 * AppLayout 侧栏底部的「对话历史」与 ChatPanel 的活跃会话共同读写此 store，
 * 避免在两个组件间手动透传 props。模块级单例，应用全局唯一。
 */
const state = reactive({
  sessions: [],
  activeSessionId: null,
  loading: false,
  // 首页发送后、跳转聊天页前透传的待发问题，聊天页 mounted 一次性消费后置空
  pendingQuestion: null
})

const ACTIVE_KEY = 'lx_active_session'

export const chatSessionStore = {
  state,

  get sessions() {
    return state.sessions
  },

  get activeSessionId() {
    return state.activeSessionId
  },

  /**
   * 拉取会话列表（首页 100 条）
   */
  async fetchSessions() {
    state.loading = true
    try {
      const res = await chatSessionApi.list(1, 100)
      const data = res.data.data || res.data
      state.sessions = data.records || []
    } catch (e) {
      console.error('获取会话列表失败:', e)
    } finally {
      state.loading = false
    }
  },

  /**
   * 设置当前活跃会话（不拉取消息，消息由 ChatPanel 负责）
   */
  setActiveSession(id) {
    state.activeSessionId = id
    if (id) {
      localStorage.setItem(ACTIVE_KEY, String(id))
    } else {
      localStorage.removeItem(ACTIVE_KEY)
    }
  },

  /**
   * 开始新对话：清空活跃会话，显示欢迎页。会话在首条消息发送时懒创建。
   */
  startNewChat() {
    this.setActiveSession(null)
    state.pendingQuestion = null//清掉残留的待发问题，避免误发
  },

  /**
   * 设置待发问题（首页→聊天页透传）
   */
  setPendingQuestion(question) {
    state.pendingQuestion = question
  },

  /**
   * 消费待发问题，读后即置空，防止刷新或重复进入误发
   */
  consumePendingQuestion() {
    const q = state.pendingQuestion
    state.pendingQuestion = null
    return q
  },

  /**
   * 创建新会话并设为活跃（带标题），返回新会话对象
   */
  async createSession(title = '新对话') {
    const res = await chatSessionApi.create(title)
    const newSession = res.data.data || res.data
    if (newSession && newSession.id) {
      this.setActiveSession(newSession.id)
      await this.fetchSessions()
    }
    return newSession
  },

  /**
   * 删除会话
   */
  async deleteSession(id) {
    await chatSessionApi.delete(id)
    if (state.activeSessionId === id) {
      this.setActiveSession(null)
    }
    state.sessions = state.sessions.filter(s => s.id !== id)
  },

  /**
   * 刷新单个会话的标题（本地 + 远程拉取）
   */
  updateSessionTitle(id, title) {
    const s = state.sessions.find(s => s.id === id)
    if (s) s.title = title
  },

  /**
   * 触发 AI 自动命名，更新本地列表标题
   */
  async autoTitle(id) {
    try {
      const res = await chatSessionApi.autoTitle(id)
      const title = res.data?.data?.title
      if (title) {
        this.updateSessionTitle(id, title)
        return title
      }
    } catch (e) {
      console.warn('AI 自动命名失败:', e)
    }
    return null
  },

  /**
   * 恢复上次活跃会话（从 localStorage）
   */
  restoreActive() {
    const saved = localStorage.getItem(ACTIVE_KEY)
    if (saved) {
      const id = parseInt(saved)
      if (!isNaN(id)) {
        state.activeSessionId = id
        return id
      }
    }
    return null
  }
}
