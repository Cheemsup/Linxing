<template>
  <div class="chat-panel-wrapper">
    <div class="session-sidebar">
      <div class="sidebar-header">
        <h3>聊天记录</h3>
        <button class="new-chat-btn" @click="newChat">+ 新对话</button>
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
        <div v-if="!activeSessionId && !branchParentId" class="welcome-hint">
          请从左侧选择一个会话，或点击"+ 新对话"开始
        </div>

        <div v-if="branchParentId" class="branch-banner">
          正在从选定消息分支提问
          <button class="branch-cancel" @click="cancelBranch">取消分支</button>
        </div>

        <template v-for="item in flattenedMessages" :key="item.id">
          <div
               :class="['message-row', { 'active-path': activePath.has(item.id) }]"
               :style="{ marginLeft: item.depth * 20 + 'px' }">
            <span
              v-if="item.children && item.children.length"
              class="tree-toggle"
              @click="toggleExpand(item.id)"
            >{{ isExpanded(item.id) ? '▼' : '▶' }}</span>
            <span v-else class="tree-toggle-placeholder"></span>

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
              <div class="message-actions">
                <button class="action-btn" @click="branchFrom(item.id)" title="从此处重新提问">分支</button>
                <button class="action-btn action-delete" @click="handleDeleteSubtree(item.id)" title="删除此分支">删除</button>
              </div>
            </div>
          </div>
        </template>

        <div v-if="loading" class="message-row">
          <span class="tree-toggle-placeholder"></span>
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
        <button @click="sendQuestion" :disabled="loading || !question.trim()">
          {{ loading ? '发送中...' : (branchParentId ? '发送分支' : '发送') }}
        </button>
      </div>
    </div>

    <ChunkContextPanel
      v-if="showContextPanel"
      ref="contextPanel"
      @close="showContextPanel = false"
      @navigate="handleNavigate"
    />
  </div>
</template>

<script>
import { ragApi, chatSessionApi } from '@/api'
import ChunkContextPanel from './ChunkContextPanel.vue'

export default {
  name: 'ChatPanel',
  components: {
    ChunkContextPanel
  },
  data() {
    return {
      question: '',
      messages: [],
      sessions: [],
      loading: false,
      activeSessionId: null,
      activeLeafId: null,
      branchParentId: null,
      collapsedNodes: {},
      showContextPanel: false
    }
  },
  computed: {
    activeSessionTitle() {
      if (!this.activeSessionId) return ''
      const s = this.sessions.find(s => s.id === this.activeSessionId)
      return s ? s.title : ''
    },
    messageMap() {
      const map = new Map()
      this.messages.forEach(m => {
        map.set(m.id, { ...m, children: [] })
      })
      this.messages.forEach(m => {
        if (m.parentId && map.has(m.parentId)) {
          map.get(m.parentId).children.push(map.get(m.id))
        }
      })
      return map
    },
    messageTree() {
      const map = this.messageMap
      const roots = []
      this.messages.forEach(m => {
        if (!m.parentId || !map.has(m.parentId)) {
          roots.push(map.get(m.id))
        }
      })
      return roots
    },
    flattenedMessages() {
      const result = []
      const walk = (node, depth) => {
        const sourceDetails = this.parseSourceDetails(node.sources)
        result.push({ ...node, depth, sourceDetails })
        if (node.children.length && this.isExpanded(node.id)) {
          node.children.forEach(child => walk(child, depth + 1))
        }
      }
      this.messageTree.forEach(root => walk(root, 0))
      return result
    },
    activePath() {
      const path = new Set()
      let current = this.activeLeafId
      const map = this.messageMap
      while (current && map.has(current)) {
        path.add(current)
        const node = map.get(current)
        current = node.parentId
      }
      return path
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
      this.branchParentId = null
      this.activeLeafId = null
      this.collapsedNodes = {}
      this.question = ''
      await this.loadMessages()
    },
    async loadMessages() {
      if (!this.activeSessionId) {
        this.messages = []
        this.activeLeafId = null
        return
      }
      try {
        const res = await chatSessionApi.getMessages(this.activeSessionId)
        let data = res.data.data || res.data
        if (!Array.isArray(data)) {
          data = []
        }
        this.messages = data
        if (data.length > 0) {
          this.activeLeafId = data[data.length - 1].id
        }
      } catch (e) {
        console.error('加载消息失败:', e)
        this.messages = []
      }
      this.$nextTick(() => this.scrollToBottom())
    },
    async sendQuestion() {
      if (!this.question.trim() || this.loading) return

      const q = this.question.trim()
      this.question = ''
      this.loading = true

      try {
        const response = await ragApi.chat({
          question: q,
          sessionId: this.activeSessionId,
          parentMessageId: this.branchParentId
        })
        const data = response.data.data || response.data

        if (data.sessionId && !this.activeSessionId) {
          this.activeSessionId = data.sessionId
          await this.fetchSessions()
        }

        this.activeLeafId = data.messageId || null
        this.branchParentId = null
        await this.loadMessages()
      } catch (error) {
        const errMsg = error.response?.data?.msg || error.response?.data?.message || error.message
        this.messages.push({
          id: -Date.now(),
          role: 'assistant',
          content: '抱歉，发生了错误: ' + errMsg,
          sources: '[]',
          parentId: null
        })
      } finally {
        this.loading = false
        this.$nextTick(() => this.scrollToBottom())
      }
    },
    newChat() {
      this.activeSessionId = null
      this.activeLeafId = null
      this.branchParentId = null
      this.messages = []
      this.collapsedNodes = {}
      this.question = ''
    },
    branchFrom(messageId) {
      this.branchParentId = messageId
      this.question = ''
      this.$nextTick(() => {
        const textarea = this.$el.querySelector('.chat-input textarea')
        if (textarea) textarea.focus()
      })
    },
    cancelBranch() {
      this.branchParentId = null
      this.question = ''
    },
    async handleDeleteSession(id) {
      if (!confirm('确定要删除此会话及其所有消息吗？此操作不可撤销。')) return
      try {
        await chatSessionApi.delete(id)
        if (this.activeSessionId === id) {
          this.activeSessionId = null
          this.messages = []
          this.activeLeafId = null
          this.branchParentId = null
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
        await this.loadMessages()
        if (this.activeLeafId === messageId || this.isDescendantOf(messageId, this.activeLeafId)) {
          this.activeLeafId = this.findNewActiveLeaf(messageId)
        }
      } catch (e) {
        console.error('删除消息子树失败:', e)
        alert('删除失败: ' + (e.response?.data?.msg || e.message))
      }
    },
    isDescendantOf(ancestorId, nodeId) {
      if (!nodeId) return false
      const map = this.messageMap
      let current = nodeId
      while (current && map.has(current)) {
        if (current === ancestorId) return true
        current = map.get(current).parentId
      }
      return false
    },
    findNewActiveLeaf(excludedId) {
      const map = this.messageMap
      const allIds = Array.from(map.keys()).filter(id => id !== excludedId && !this.isDescendantOf(excludedId, id))
      if (allIds.length === 0) return null
      return allIds[allIds.length - 1]
    },
    toggleExpand(id) {
      this.$set(this.collapsedNodes, id, !this.collapsedNodes[id])
    },
    isExpanded(id) {
      return !this.collapsedNodes[id]
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
  justify-content: space-between;
  align-items: center;
}

.sidebar-header h3 {
  margin: 0;
  font-size: 15px;
  color: #333;
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

.branch-banner {
  background: #fff3e0;
  border: 1px solid #ffcc80;
  border-radius: 6px;
  padding: 8px 14px;
  margin-bottom: 12px;
  font-size: 13px;
  color: #e65100;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.branch-cancel {
  background: none;
  border: 1px solid #e65100;
  color: #e65100;
  padding: 2px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.branch-cancel:hover {
  background: #e65100;
  color: white;
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
  transition: background 0.15s;
}

.message-row.active-path {
  background: #e8f5e9;
  border-left: 2px solid #4caf50;
}

.tree-toggle {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  font-size: 10px;
  color: #888;
  cursor: pointer;
  margin-right: 4px;
  margin-top: 14px;
  user-select: none;
  border-radius: 3px;
}

.tree-toggle:hover {
  background: #e0e0e0;
  color: #333;
}

.tree-toggle-placeholder {
  flex-shrink: 0;
  width: 16px;
  margin-right: 4px;
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
</style>
