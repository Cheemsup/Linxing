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
                  <template v-if="item.stepEvents && item.stepEvents.length">
                    <div class="collapsible-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'step') }">
                      <div class="panel-header" @click="togglePanel(item.id, 'step')">
                        <span class="panel-toggle">{{ isPanelCollapsed(item.id, 'step') ? '▶' : '▼' }}</span>
                        <span class="panel-title">推理过程</span>
                        <span class="panel-badge">{{ item.stepEvents.length }}步</span>
                      </div>
                      <div v-show="!isPanelCollapsed(item.id, 'step')" class="panel-body">
                        <template v-for="(step, idx) in item.stepEvents" :key="idx">
                          <div v-if="step.eventType === 'thinking'" class="collapsible-panel sub-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'thinking_' + idx) }">
                            <div class="panel-header" @click="togglePanel(item.id, 'thinking_' + idx)">
                              <span class="panel-toggle">{{ isPanelCollapsed(item.id, 'thinking_' + idx) ? '▶' : '▼' }}</span>
                              <span class="step-icon">💭</span>
                              <span class="panel-title">{{ step.thinkingContent ? '思考推理中...' : '正在思考...' }}</span>
                            </div>
                            <div v-show="!isPanelCollapsed(item.id, 'thinking_' + idx)" class="panel-body">
                              <div v-if="step.thinkingContent" class="thinking-content">{{ step.thinkingContent }}</div>
                              <div v-else class="step-placeholder">等待推理内容...</div>
                            </div>
                          </div>
                          <div v-else :class="['step-item', getStepClass(step)]">
                            <span class="step-icon">{{ getStepIcon(step) }}</span>
                            <span class="step-text">{{ formatStepText(step) }}</span>
                          </div>
                        </template>
                      </div>
                    </div>
                    <div class="collapsible-panel answer-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'answer') }">
                      <div class="panel-header" @click="togglePanel(item.id, 'answer')">
                        <span class="panel-toggle">{{ isPanelCollapsed(item.id, 'answer') ? '▶' : '▼' }}</span>
                        <span class="panel-title">回答详情</span>
                        <span class="panel-badge">已生成</span>
                      </div>
                      <div v-show="!isPanelCollapsed(item.id, 'answer')" class="panel-body">
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
                      </div>
                    </div>
                  </template>
                  <template v-else>
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
                  </template>
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
              <div class="bot-message agent-output">

                <div class="collapsible-panel" :class="{ collapsed: stepCollapsed }">
                  <div class="panel-header" @click="stepCollapsed = !stepCollapsed">
                    <span class="panel-toggle">{{ stepCollapsed ? '▶' : '▼' }}</span>
                    <span class="panel-title">推理过程</span>
                    <span class="panel-badge" v-if="stepEvents.length">{{ stepEvents.length }}步</span>
                  </div>
                  <div v-show="!stepCollapsed" class="panel-body">
                    <div v-if="!stepEvents.length && !isStreaming" class="step-placeholder">
                      等待推理开始...
                    </div>
                    <template v-for="(step, idx) in stepEvents" :key="idx">
                      <div v-if="step.eventType === 'thinking'" class="collapsible-panel sub-panel" :class="{ collapsed: step.thinkingCollapsed }">
                        <div class="panel-header" @click="step.thinkingCollapsed = !step.thinkingCollapsed">
                          <span class="panel-toggle">{{ step.thinkingCollapsed ? '▶' : '▼' }}</span>
                          <span class="step-icon">💭</span>
                          <span class="panel-title">{{ step.thinkingContent ? '思考推理中...' : '正在思考...' }}</span>
                        </div>
                        <div v-show="!step.thinkingCollapsed" class="panel-body">
                          <div v-if="step.thinkingContent" class="thinking-content">{{ step.thinkingContent }}</div>
                          <div v-else class="step-placeholder">等待推理内容...</div>
                        </div>
                      </div>
                      <div v-else :class="['step-item', getStepClass(step)]">
                        <span class="step-icon">{{ getStepIcon(step) }}</span>
                        <span class="step-text">{{ formatStepText(step) }}</span>
                      </div>
                    </template>
                    <div v-if="isStreaming && !stepEvents.some(s => s.eventType === 'thinking' && !s.thinkingContent)" class="step-item step-thinking">
                      <span class="step-icon">💭</span>
                      <span>正在思考...</span>
                    </div>
                  </div>
                </div>

                <div class="collapsible-panel answer-panel" :class="{ collapsed: answerCollapsed }">
                  <div class="panel-header" @click="answerCollapsed = !answerCollapsed">
                    <span class="panel-toggle">{{ answerCollapsed ? '▶' : '▼' }}</span>
                    <span class="panel-title">回答详情</span>
                    <span class="panel-badge" v-if="streamingText || isStreaming">
                      {{ isStreaming ? '生成中' : '已生成' }}
                    </span>
                  </div>
                  <div v-show="!answerCollapsed" class="panel-body">
                    <div v-if="isStreaming" class="answer streaming-answer streaming-plain">{{ streamingText }}</div>
                    <div v-else-if="streamingText" class="answer" v-html="formatAnswer(streamingText)"></div>
                    <div v-else class="step-placeholder">
                      等待回答...
                    </div>
                    <span v-if="isStreaming" class="streaming-cursor">|</span>
                  </div>
                </div>

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
import { ragApi, chatSessionApi } from '@/api/rag/chat'
import ChunkContextPanel from './ChunkContextPanel.vue'
import ChatTreePanel from './ChatTreePanel.vue'
import { chatTreeStore } from '@/stores/rag/chatTreeStore'

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
      showTreeModal: false,
      streamingText: '',
      isStreaming: false,
      stepEvents: [],
      stepCollapsed: false,
      answerCollapsed: false,
      tokenBuffer: '',
      flushTimer: null,
      tokenGroups: {},
      currentStreamStepNumber: 0,
      messagePanelState: {}
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
      this.messagePanelState = {}
      await this.loadMessages()
    },
    async loadMessages(activeLeafId = null) {
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
        if (activeLeafId) {
          chatTreeStore.setActiveLeaf(activeLeafId)
        } else if (data.length > 0) {
          const lastAssistant = data.filter(m => m.role === 'assistant').pop()
          chatTreeStore.setActiveLeaf(lastAssistant ? lastAssistant.id : data[data.length - 1].id)
        }
      } catch (e) {
        console.error('加载消息失败:', e)
        chatTreeStore.clearMessages()
      }
      this.$nextTick(() => this.scrollToBottom())
    },

    resetStreamState() {
      this.streamingText = ''
      this.isStreaming = false
      this.stepEvents = []
      this.stepCollapsed = false
      this.answerCollapsed = false
      this.tokenBuffer = ''
      this.tokenGroups = {}
      this.currentStreamStepNumber = 0
      if (this.flushTimer) {
        clearTimeout(this.flushTimer)
        this.flushTimer = null
      }
    },

    async sendQuestion() {
      if (!this.question.trim() || this.loading) return

      const q = this.question.trim()
      this.question = ''
      this.loading = true
      this.resetStreamState()

      this.tempUserMsg = { content: q }
      this.$nextTick(() => this.scrollToBottom())

      let parentMessageId
      if (chatTreeStore.state.branchParentId) {
        const map = chatTreeStore.getMessageMap()
        const branchNode = map.get(chatTreeStore.state.branchParentId)
        parentMessageId = branchNode ? branchNode.parentId : null
      } else {
        parentMessageId = chatTreeStore.state.activeLeafId
      }

      const vm = this
      ragApi.chatStream({
        question: q,
        sessionId: this.activeSessionId,
        parentMessageId: parentMessageId,
        onStep(data) {
          const stepNumber = data.stepNumber || 0

          // 先 flush 剩余 token，确保 tokenGroups 中有最新内容
          vm.flushTokenBuffer()

          // 将该 stepNumber 累积的 token 归入对应 step
          const groupText = vm.tokenGroups[stepNumber] || ''
          delete vm.tokenGroups[stepNumber]

          const stepData = {
            eventType: data.eventType,
            stepNumber: stepNumber,
            phase: data.phase,
            toolName: data.toolName,
            toolArguments: data.toolArguments,
            toolResult: data.toolResult,
            answer: data.answer,
            error: data.error,
            finalStep: data.finalStep,
            thinkingContent: '',
            thinkingCollapsed: false
          }

          if (data.phase === 'answer') {
            // 最终回答：token 迁移到 streamingText（回答详情区域）
            if (groupText) {
              vm.streamingText += groupText
            }
          } else if (data.phase === 'cache') {
            // 缓存命中：直接使用 answer，无需 token 归并
          } else {
            // 推理阶段：token 归入 thinking step
            if (data.eventType === 'thinking' && data.answer) {
              stepData.thinkingContent = data.answer
            } else if (groupText) {
              stepData.thinkingContent = groupText
            }
          }

          vm.stepEvents.push(stepData)
          vm.$nextTick(() => vm.scrollToBottom())
        },
        onStream(data) {
          if (!vm.isStreaming) {
            vm.isStreaming = true
          }
          vm.currentStreamStepNumber = data.stepNumber || 0
          // token batching — 30ms 批量 flush
          vm.tokenBuffer += data.token
          if (!vm.flushTimer) {
            vm.flushTimer = setTimeout(() => vm.flushTokenBuffer(), 30)
          }
        },
        onResult(data) {
          vm.isStreaming = false
          vm.stepCollapsed = true
          vm.answerCollapsed = false

          if (data.sessionId && !vm.activeSessionId) {
            vm.activeSessionId = data.sessionId
            vm.fetchSessions()
          }
          chatTreeStore.clearBranch()

          const userMsg = {
            id: -Date.now(),
            userId: null,
            sessionId: data.sessionId,
            parentId: parentMessageId,
            role: 'user',
            content: q,
            sources: '[]',
            createdAt: new Date().toISOString()
          }

          const assistantMsg = {
            id: data.messageId || -Date.now() + 1,
            userId: null,
            sessionId: data.sessionId,
            parentId: userMsg.id,
            role: 'assistant',
            content: data.answer,
            sources: JSON.stringify(data.sourceDetails || []),
            createdAt: new Date().toISOString(),
            stepEvents: [...vm.stepEvents]
          }

          vm.messagePanelState[assistantMsg.id] = { step: true, answer: false }

          chatTreeStore.state.messages.push(userMsg, assistantMsg)
          chatTreeStore.setActiveLeaf(assistantMsg.id)

          vm.$nextTick(() => vm.scrollToBottom())
        },
        onError(data) {
          vm.tempUserMsg = null
          vm.loading = false
          vm.resetStreamState()
          vm.stepEvents.push({
            eventType: 'error',
            error: data.message || '未知错误',
            finalStep: true
          })
          chatTreeStore.state.messages.push({
            id: -Date.now(),
            role: 'assistant',
            content: '抱歉，发生了错误: ' + (data.message || '未知错误'),
            sources: '[]',
            parentId: null
          })
          vm.$nextTick(() => vm.scrollToBottom())
        },
        onDone() {
          vm.tempUserMsg = null
          vm.loading = false
          vm.isStreaming = false
          vm.$nextTick(() => vm.scrollToBottom())
          vm.fetchSessions()
        }
      })
    },

    getStepClass(step) {
      switch (step.eventType) {
        case 'tool_call': return 'step-tool'
        case 'tool_result': return 'step-tool'
        case 'thinking': return 'step-thinking'
        case 'error': return 'step-error'
        case 'final': return 'step-final'
        case 'cache_hit': return 'step-cache'
        default: return ''
      }
    },

    getStepIcon(step) {
      switch (step.eventType) {
        case 'tool_call': return '🔧'
        case 'tool_result': return '📋'
        case 'thinking': return '💭'
        case 'error': return '❌'
        case 'final': return '✅'
        case 'cache_hit': return '⚡'
        default: return '📌'
      }
    },

    formatStepText(step) {
      switch (step.eventType) {
        case 'tool_call': {
          let text = `工具调用: ${step.toolName || 'unknown'}`
          if (step.toolArguments) {
            const argsStr = typeof step.toolArguments === 'string'
              ? step.toolArguments
              : JSON.stringify(step.toolArguments)
            text += ` (${argsStr.length > 50 ? argsStr.substring(0, 50) + '...' : argsStr})`
          }
          return text
        }
        case 'tool_result': {
          let text = `工具结果: ${step.toolName || 'unknown'}`
          if (step.toolResult !== undefined && step.toolResult !== null) {
            const resultStr = String(step.toolResult)
            text += ` → ${resultStr.length > 60 ? resultStr.substring(0, 60) + '...' : resultStr}`
          }
          return text
        }
        case 'thinking':
          return step.answer ? `思考: ${step.answer}` : '思考推理中...'
        case 'error':
          return `错误: ${step.error || '未知'}`
        case 'final':
          return '推理完成'
        case 'cache_hit':
          return '缓存命中'
        default:
          return `${step.eventType}: ${JSON.stringify(step).substring(0, 80)}`
      }
    },

    flushTokenBuffer() {
      if (this.tokenBuffer) {
        const sn = this.currentStreamStepNumber
        if (!this.tokenGroups[sn]) {
          this.tokenGroups[sn] = ''
        }
        this.tokenGroups[sn] += this.tokenBuffer
        this.streamingText += this.tokenBuffer
        this.tokenBuffer = ''
        this.$nextTick(() => this.scrollToBottom())
      }
      this.flushTimer = null
    },

    isPanelCollapsed(messageId, panelType) {
      const state = this.messagePanelState[messageId]
      if (!state) return true
      return state[panelType]
    },

    togglePanel(messageId, panelType) {
      if (!this.messagePanelState[messageId]) {
        this.messagePanelState[messageId] = { step: true, answer: false }
      }
      this.messagePanelState[messageId][panelType] = !this.messagePanelState[messageId][panelType]
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
        let newActiveLeaf = null
        if (currentActiveLeaf === messageId || chatTreeStore.isDescendantOf(messageId, currentActiveLeaf)) {
          newActiveLeaf = chatTreeStore.findNewActiveLeaf(messageId)
          chatTreeStore.setActiveLeaf(newActiveLeaf)
        } else {
          newActiveLeaf = currentActiveLeaf
        }
        await this.loadMessages(newActiveLeaf)
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
@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.streaming-cursor {
  display: inline-block;
  animation: blink 0.8s infinite;
  color: #1a73e8;
  font-weight: bold;
  margin-left: 1px;
}

.streaming-answer {
  display: inline;
}

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

.message.assistant .message-content {
  display: block;
  width: 100%;
}

.bot-message {
  background: white;
  color: #333;
  box-shadow: 0 1px 2px rgba(0,0,0,0.1);
  width: 100%;
}

.agent-output {
  width: 100%;
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

.collapsible-panel {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  margin-bottom: 8px;
  overflow: hidden;
  background: white;
  transition: all 0.25s ease;
}

.collapsible-panel.collapsed {
  background: #fafbfc;
}

.panel-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
  background: #f8f9fa;
  border-bottom: 1px solid #eee;
  transition: background 0.15s;
}

.collapsed > .panel-header {
  border-bottom: none;
}

.panel-header:hover {
  background: #eef1f5;
}

.panel-toggle {
  font-size: 11px;
  color: #888;
  flex-shrink: 0;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #444;
}

.panel-badge {
  font-size: 11px;
  color: #1a73e8;
  background: #e8f0fe;
  padding: 1px 7px;
  border-radius: 10px;
  margin-left: auto;
}

.panel-body {
  padding: 10px 12px;
  max-height: 300px;
  overflow-y: auto;
}

.answer-panel .panel-body {
  max-height: 400px;
  line-height: 1.6;
}

.step-placeholder {
  color: #aaa;
  font-style: italic;
  font-size: 13px;
  text-align: center;
  padding: 12px 0;
}

.step-item {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 5px 0;
  font-size: 13px;
  line-height: 1.5;
  border-bottom: 1px solid #f5f5f5;
}

.step-item:last-child {
  border-bottom: none;
}

.step-icon {
  flex-shrink: 0;
  font-size: 14px;
  margin-top: 1px;
}

.step-text {
  word-break: break-word;
  color: #444;
}

.step-tool .step-text {
  color: #1565c0;
}

.step-thinking .step-text {
  color: #666;
  font-style: italic;
}

.step-error .step-text {
  color: #c62828;
  font-weight: 500;
}

.step-final .step-text {
  color: #2e7d32;
  font-weight: 500;
}

.thinking-content {
  white-space: pre-wrap;
  color: #666;
  font-style: italic;
  border-bottom: 1px solid #eee;
  padding-bottom: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.5;
}

.streaming-plain {
  white-space: pre-wrap;
}

.sub-panel {
  margin-top: 6px;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  background: #fafafa;
}

.sub-panel .panel-header {
  padding: 4px 8px;
  font-size: 13px;
}

.sub-panel .panel-body {
  padding: 6px 8px;
}

.sub-panel .thinking-content {
  border-bottom: none;
  padding-bottom: 0;
  margin-bottom: 0;
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
