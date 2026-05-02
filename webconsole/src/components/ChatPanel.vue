<template>
  <div class="chat-panel-wrapper">
    <div class="session-sidebar">
      <div class="sidebar-header">
        <h3>聊天记录</h3>
        <div class="sidebar-header-actions">
          <button class="tree-btn" @click="showTreeModal = true" :disabled="!activeSessionId || !chatTreeStore.state.messages.length" title="查看对话树">🌲 Chat树</button>
          <button class="new-chat-btn" @click="newChat">+ 新对话</button>
        </div>
      </div>
      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          :class="['session-item', { active: s.id === activeSessionId }]"
          @click="switchSession(s.id)"
        >
          <div class="session-info">
            <span class="session-title">{{ s.title }}</span>
            <span class="session-meta">{{ s.messageCount }} 条消息</span>
          </div>
          <button class="session-delete" @click.stop="handleDeleteSession(s.id)" title="删除会话">&times;</button>
        </div>
        <div v-if="!sessions.length" class="no-sessions">
          暂无聊天记录，点击上方按钮开始新对话
        </div>
      </div>
    </div>

    <div class="chat-panel">
      <div class="chat-messages" ref="messagesContainer">
        <div v-if="!activeSessionId && !chatTreeStore.state.branchParentId" class="welcome-hint">
          请从左侧选择一个会话，或点击"+ 新对话"开始
        </div>

        <template v-for="item in allMessages" :key="item.id">
          <div class="message-row">
            <div :class="['message', item.role]">
              <div class="message-content">
                <div v-if="item.role === 'user'" class="user-message">
                  <strong>你:</strong> {{ item.content }}
                </div>
                <div v-else class="bot-message">
                  <strong>助手:</strong>
                  <div class="answer" v-html="formatAnswer(item.content)"></div>
                  <div v-if="item.sourceDetails && item.sourceDetails.length" class="sources">
                    <span class="source-label">来源:</span>
                    <span
                      v-for="(source, si) in item.sourceDetails"
                      :key="si"
                      class="source-tag clickable"
                      @click="openChunkContext(source)"
                      :title="'点击查看上下文: ' + (source.titlePath || source.fileName)"
                    >
                      {{ source.fileName }}{{ source.titlePath ? ' > ' + source.titlePath : '' }}
                    </span>
                  </div>
                  <div v-else-if="item.sources && item.sources.length" class="sources">
                    <span class="source-label">来源:</span>
                    <span v-for="source in item.sources" :key="source" class="source-tag">{{ source }}</span>
                  </div>
                </div>
              </div>
              <div v-if="item.role === 'user'" class="message-actions">
                <button class="action-btn" @click="reAsk(item.id, item.content)" title="从此处分支并重新提问">重新提问</button>
                <button class="action-btn" @click="branchFrom(item.id)" title="从此处重新提问">分支</button>
                <button class="action-btn action-delete" @click="handleDeleteSubtree(item.id)" title="删除此分支">删除</button>
                <button v-if="item.id === chatTreeStore.state.branchParentId" class="action-btn action-cancel-branch" @click="cancelBranch" title="取消从此处分支">取消分支</button>
              </div>
            </div>
          </div>
        </template>

        <div v-if="tempUserMsg" class="message-row">
          <div class="message user">
            <div class="message-content">
              <div class="user-message">
                <strong>你:</strong> {{ tempUserMsg.content }}
              </div>
            </div>
          </div>
        </div>

        <div v-if="loading" class="message-row">
          <div class="message assistant">
            <div class="message-content">
              <div class="bot-message">
                <strong>助手:</strong> <span class="loading">思考中...</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-input">
        <textarea
          v-model="question"
          @keydown.enter.exact.prevent="sendQuestion"
          placeholder="输入你的问题，例如：我的笔记中关于XXX的内容是什么？"
          rows="3"
        ></textarea>
        <button
          @click="sendQuestion"
          :disabled="loading || !question.trim()"
          :class="chatTreeStore.state.branchParentId ? 'btn-send-branch' : ''"
        >
          {{ loading ? '发送中...' : (chatTreeStore.state.branchParentId ? '发送新分支消息' : '发送') }}
        </button>
      </div>
    </div>

    <div v-if="showContextPanel" class="context-overlay" @click.self="showContextPanel = false">
      <div class="context-modal">
        <ChunkContextPanel
          ref="contextPanel"
          @close="showContextPanel = false"
          @navigate="handleNavigate"
        />
      </div>
    </div>

    <div v-if="showTreeModal" class="tree-overlay" @click.self="showTreeModal = false">
      <div class="tree-modal">
        <ChatTreePanel
          :roots="userQuestionRoots"
          :active-path="userQuestionActivePath"
          @close="showTreeModal = false"
          @select="handleTreeSelect"
        />
      </div>
    </div>
  </div>
</template>

<script>
import { ragApi, chatSessionApi } from '@/api'
import ChunkContextPanel from './ChunkContextPanel.vue'
import ChatTreePanel from './ChatTreePanel.vue'
import { chatTreeStore } from '@/utils/chatTreeStore'

export default {
  name: 'ChatPanel',
  components: {
    ChunkContextPanel,
    ChatTreePanel
  },
  data() {
    return {
      question: '',
      sessions: [],
      loading: false,
      activeSessionId: null,
      tempUserMsg: null,
      showContextPanel: false,
      showTreeModal: false
    }
  },
  computed: {
    chatTreeStore() {
      return chatTreeStore
    },
    activeSessionTitle() {
      if (!this.activeSessionId) return ''
      const s = this.sessions.find(s => s.id === this.activeSessionId)
      return s ? s.title : ''
    },
    messageMap() {
      return chatTreeStore.getMessageMap()
    },
    messageTree() {
      const map = this.messageMap
      const roots = []
      chatTreeStore.state.messages.forEach(m => {
        if (!m.parentId || !map.has(m.parentId)) {
          roots.push(map.get(m.id))
        }
      })
      return roots
    },
    allMessages() {
      const result = []
      const activeLeafId = chatTreeStore.state.activeLeafId
      if (!activeLeafId || !this.messageMap.has(activeLeafId)) {
        return result
      }
      const roots = this.messageTree

      const containsLeaf = (node) => {
        if (node.id === activeLeafId) return true
        if (node.children && node.children.length) {
          return node.children.some(child => containsLeaf(child))
        }
        return false
      }

      const walk = (node) => {
        const enriched = { ...node, sourceDetails: this.parseSourceDetails(node.sources) }
        result.push(enriched)
        if (node.id === activeLeafId) {
          return
        }
        if (node.children && node.children.length) {
          const activeChild = node.children.find(c => containsLeaf(c))
          if (activeChild) {
            for (const child of node.children) {
              if (child !== activeChild && child.role === 'assistant') {
                const enrichedSibling = { ...child, sourceDetails: this.parseSourceDetails(child.sources) }
                result.push(enrichedSibling)
              }
            }
            walk(activeChild)
          }
        }
      }

      for (const root of roots) {
        if (containsLeaf(root)) {
          walk(root)
          break
        }
      }
      return result
    },
    userQuestionRoots() {
      const buildUserTree = (node) => {
        const questions = []
        if (node.role === 'user') {
          const userNode = { id: node.id, content: node.content, children: [] }
          node.children.forEach(child => {
            if (child.role === 'assistant') {
              child.children.forEach(grandChild => {
                const subs = buildUserTree(grandChild)
                subs.forEach(sub => userNode.children.push(sub))
              })
            } else if (child.role === 'user') {
              const subs = buildUserTree(child)
              subs.forEach(sub => userNode.children.push(sub))
            }
          })
          questions.push(userNode)
        } else {
          node.children.forEach(child => {
            const subs = buildUserTree(child)
            subs.forEach(sub => questions.push(sub))
          })
        }
        return questions
      }
      const roots = []
      this.messageTree.forEach(root => {
        const qs = buildUserTree(root)
        qs.forEach(q => roots.push(q))
      })
      return roots
    },
    userQuestionActivePath() {
      return chatTreeStore.getUserActivePath()
    },
    activePath() {
      return chatTreeStore.getActivePathIds()
    }
  },
  watch: {
    activeSessionId(val) {
      if (val) {
        localStorage.setItem('lx_active_session', String(val))
      } else {
        localStorage.removeItem('lx_active_session')
      }
    }
  },
  mounted() {
    this.fetchSessions()
    const saved = localStorage.getItem('lx_active_session')
    if (saved) {
      const id = parseInt(saved)
      if (!isNaN(id)) {
        this.switchSession(id)
      }
    }
  },
  methods: {
    async fetchSessions() {
      try {
        const res = await chatSessionApi.list(1, 100)
        const data = res.data.data || res.data
        this.sessions = data.records || []
      } catch (e) {
        console.error('获取会话列表失败:', e)
      }
    },
    async switchSession(id) {
      this.activeSessionId = id
      chatTreeStore.clearBranch()
      chatTreeStore.setActiveLeaf(null)
      this.question = ''
      await this.loadMessages()
    },
    async loadMessages() {
      if (!this.activeSessionId) {
        chatTreeStore.clearMessages()
        return
      }
      try {
        const res = await chatSessionApi.getMessages(this.activeSessionId)
        let data = res.data.data || res.data
        if (!Array.isArray(data)) {
          data = []
        }
        chatTreeStore.setMessages(data)
        if (data.length > 0) {
          chatTreeStore.setActiveLeaf(data[data.length - 1].id)
        }
      } catch (e) {
        console.error('加载消息失败:', e)
        chatTreeStore.clearMessages()
      }
      this.$nextTick(() => this.scrollToBottom())
    },
    async sendQuestion() {
      if (!this.question.trim() || this.loading) return

      const q = this.question.trim()
      this.question = ''
      this.loading = true

      this.tempUserMsg = { content: q }
      this.$nextTick(() => this.scrollToBottom())

      try {
        const response = await ragApi.chat({
          question: q,
          sessionId: this.activeSessionId,
          parentMessageId: chatTreeStore.state.branchParentId || chatTreeStore.state.activeLeafId
        })
        const data = response.data.data || response.data

        if (data.sessionId && !this.activeSessionId) {
          this.activeSessionId = data.sessionId
          await this.fetchSessions()
        }

        chatTreeStore.setActiveLeaf(data.messageId || null)
        chatTreeStore.clearBranch()
        await this.loadMessages()
      } catch (error) {
        const errMsg = error.response?.data?.msg || error.response?.data?.message || error.message
        chatTreeStore.state.messages.push({
          id: -Date.now(),
          role: 'assistant',
          content: '抱歉，发生了错误: ' + errMsg,
          sources: '[]',
          parentId: null
        })
      } finally {
        this.tempUserMsg = null
        this.loading = false
        this.$nextTick(() => this.scrollToBottom())
      }
    },
    async newChat() {
      const title = prompt('请输入新对话的标题:')
      if (!title || !title.trim()) return

      try {
        const res = await chatSessionApi.create(title.trim())
        const newSession = res.data.data || res.data
        if (newSession && newSession.id) {
          this.activeSessionId = newSession.id
          chatTreeStore.clearMessages()
          this.question = ''
          await this.fetchSessions()
        }
      } catch (e) {
        console.error('创建会话失败:', e)
        alert('创建会话失败: ' + (e.response?.data?.msg || e.message))
      }
    },
    branchFrom(messageId) {
      chatTreeStore.setBranchParent(messageId)
      this.question = ''
      this.$nextTick(() => {
        const textarea = this.$el.querySelector('.chat-input textarea')
        if (textarea) textarea.focus()
      })
    },
    cancelBranch() {
      chatTreeStore.clearBranch()
      this.question = ''
    },
    async handleDeleteSession(id) {
      if (!confirm('确定要删除此会话及其所有消息吗？此操作不可撤销。')) return
      try {
        await chatSessionApi.delete(id)
        if (this.activeSessionId === id) {
          this.activeSessionId = null
          chatTreeStore.clearMessages()
        }
        this.sessions = this.sessions.filter(s => s.id !== id)
      } catch (e) {
        console.error('删除会话失败:', e)
        alert('删除失败: ' + (e.response?.data?.msg || e.message))
      }
    },
    async handleDeleteSubtree(messageId) {
      if (!confirm('确定要删除此消息及其所有回复吗？')) return
      try {
        await chatSessionApi.deleteSubtree(messageId)
        const currentActiveLeaf = chatTreeStore.state.activeLeafId
        if (currentActiveLeaf === messageId || chatTreeStore.isDescendantOf(messageId, currentActiveLeaf)) {
          chatTreeStore.setActiveLeaf(chatTreeStore.findNewActiveLeaf(messageId))
        }
        await this.loadMessages()
      } catch (e) {
        console.error('删除消息子树失败:', e)
        alert('删除失败: ' + (e.response?.data?.msg || e.message))
      }
    },
    handleTreeSelect(nodeId) {
      const leafId = chatTreeStore.findLeafDescendant(nodeId)
      chatTreeStore.setActiveLeaf(leafId)
      this.showTreeModal = false
      this.$nextTick(() => this.scrollToBottom())
    },
    reAsk(messageId, content) {
      chatTreeStore.setBranchParent(messageId)
      this.question = content
      this.$nextTick(() => {
        const textarea = this.$el.querySelector('.chat-input textarea')
        if (textarea) {
          textarea.focus()
          textarea.setSelectionRange(textarea.value.length, textarea.value.length)
        }
      })
    },
    parseSourceDetails(sourcesStr) {
      try {
        return JSON.parse(sourcesStr || '[]')
      } catch {
        return []
      }
    },
    openChunkContext(sourceDetail) {
      if (!sourceDetail || !sourceDetail.chunkId) return
      this.showContextPanel = true
      this.$nextTick(() => {
        if (this.$refs.contextPanel) {
          this.$refs.contextPanel.loadContext(sourceDetail.chunkId)
        }
      })
    },
    handleNavigate(chunkId) {
      this.$nextTick(() => {
        if (this.$refs.contextPanel) {
          this.$refs.contextPanel.loadContext(chunkId)
        }
      })
    },
    formatAnswer(text) {
      if (!text) return ''
      return text.replace(/\n/g, '<br>').replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    },
    scrollToBottom() {
      const container = this.$refs.messagesContainer
      if (container) {
        container.scrollTop = container.scrollHeight
      }
    }
  }
}
</script>

<style scoped>
.chat-panel-wrapper {
  display: flex;
  height: 100%;
}

.session-sidebar {
  width: 260px;
  min-width: 260px;
  border-right: 1px solid #e0e0e0;
  background: #fafafa;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid #e0e0e0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sidebar-header h3 {
  margin: 0;
  font-size: 15px;
  color: #333;
}

.sidebar-header-actions {
  display: flex;
  gap: 8px;
}

.tree-btn {
  padding: 6px 10px;
  background: white;
  color: #667eea;
  border: 1px solid #667eea;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
  transition: all 0.2s;
}

.tree-btn:hover:not(:disabled) {
  background: #667eea;
  color: white;
}

.tree-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.new-chat-btn {
  padding: 6px 14px;
  background: #1a73e8;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
}

.new-chat-btn:hover {
  background: #1557b0;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.session-item {
  padding: 12px 16px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #f0f0f0;
  transition: background 0.15s;
}

.session-item:hover {
  background: #f0f0f0;
}

.session-item.active {
  background: #e3f2fd;
  border-left: 3px solid #1a73e8;
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-title {
  display: block;
  font-size: 14px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-meta {
  font-size: 12px;
  color: #999;
}

.session-delete {
  background: none;
  border: none;
  color: #ccc;
  font-size: 18px;
  cursor: pointer;
  padding: 0 4px;
  line-height: 1;
  flex-shrink: 0;
}

.session-delete:hover {
  color: #e53935;
}

.no-sessions {
  padding: 24px 16px;
  text-align: center;
  color: #999;
  font-size: 13px;
}

.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
}

.welcome-hint {
  text-align: center;
  color: #999;
  padding: 48px 16px;
  font-size: 14px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f5f5f5;
  border-radius: 8px;
  margin-bottom: 16px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 8px;
  padding: 4px 8px;
  border-radius: 6px;
}

.message {
  flex: 1;
  min-width: 0;
}

.message-content {
  display: inline-block;
  max-width: 100%;
  padding: 10px 14px;
  border-radius: 12px;
  text-align: left;
}

.user-message {
  background: #1a73e8;
  color: white;
  border-radius: 12px;
  padding: 10px 14px;
}

.bot-message {
  background: white;
  color: #333;
  box-shadow: 0 1px 2px rgba(0,0,0,0.1);
}

.answer {
  margin-top: 4px;
  line-height: 1.6;
}

.sources {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #eee;
  font-size: 12px;
}

.source-label {
  color: #666;
  margin-right: 8px;
}

.source-tag {
  display: inline-block;
  background: #e3f2fd;
  color: #1565c0;
  padding: 2px 8px;
  border-radius: 4px;
  margin-right: 4px;
  font-size: 11px;
}

.source-tag.clickable {
  cursor: pointer;
  transition: all 0.15s;
}

.source-tag.clickable:hover {
  background: #bbdefb;
  text-decoration: underline;
}

.loading {
  color: #666;
  font-style: italic;
}

.message-actions {
  margin-top: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}

.message:hover .message-actions {
  opacity: 1;
}

.action-btn {
  background: none;
  border: 1px solid #ddd;
  color: #666;
  padding: 1px 8px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 11px;
  margin-right: 4px;
}

.action-btn:hover {
  background: #f0f0f0;
  color: #333;
}

.action-delete:hover {
  border-color: #e53935;
  color: #e53935;
  background: #ffebee;
}

.action-cancel-branch {
  color: #e65100;
  border-color: #e65100;
}

.action-cancel-branch:hover {
  background: #fff3e0;
  color: #e65100;
  border-color: #e65100;
}

.chat-input {
  display: flex;
  gap: 12px;
}

.chat-input textarea {
  flex: 1;
  padding: 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  resize: none;
  font-size: 14px;
}

.chat-input button {
  padding: 12px 24px;
  background: #1a73e8;
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  white-space: nowrap;
}

.chat-input button:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.chat-input .btn-send-branch {
  background: #e65100;
}

.chat-input .btn-send-branch:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.context-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.context-modal {
  background: white;
  border-radius: 12px;
  width: 90%;
  max-width: 700px;
  height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
  overflow: hidden;
  animation: slideUp 0.25s ease-out;
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

.tree-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1001;
}

.tree-modal {
  background: white;
  border-radius: 12px;
  width: 90%;
  max-width: 960px;
  height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
  overflow: hidden;
  animation: slideUp 0.25s ease-out;
}
</style>
