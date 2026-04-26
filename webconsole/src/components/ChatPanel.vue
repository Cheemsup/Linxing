<template>
  <div class="chat-panel">
    <div class="chat-messages" ref="messagesContainer">
      <div v-for="(msg, index) in messages" :key="index" :class="['message', msg.type]">
        <div class="message-content">
          <div v-if="msg.type === 'user'" class="user-message">
            <strong>你:</strong> {{ msg.content }}
          </div>
          <div v-else class="bot-message">
            <strong>助手:</strong>
            <div class="answer" v-html="formatAnswer(msg.content)"></div>
            <div v-if="msg.sources && msg.sources.length" class="sources">
              <span class="source-label">来源:</span>
              <span v-for="source in msg.sources" :key="source" class="source-tag">{{ source }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-if="loading" class="message bot">
        <div class="message-content">
          <div class="bot-message">
            <strong>助手:</strong> <span class="loading">思考中...</span>
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
        {{ loading ? '发送中...' : '发送' }}
      </button>
    </div>
  </div>
</template>

<script>
import { ragApi } from '@/api'

export default {
  name: 'ChatPanel',
  data() {
    return {
      question: '',
      messages: [],
      loading: false,
      sessionId: 'session-' + Date.now()
    }
  },
  methods: {
    async sendQuestion() {
      if (!this.question.trim() || this.loading) return

      const q = this.question.trim()
      this.messages.push({ type: 'user', content: q })
      this.question = ''
      this.loading = true

      try {
        const response = await ragApi.chat(q, this.sessionId)
        const data = response.data.data || response.data
        this.messages.push({
          type: 'bot',
          content: data.answer,
          sources: data.sources
        })
      } catch (error) {
        this.messages.push({
          type: 'bot',
          content: '抱歉，发生了错误: ' + (error.response?.data?.message || error.message)
        })
      } finally {
        this.loading = false
        this.$nextTick(() => {
          this.scrollToBottom()
        })
      }
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
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f5f5f5;
  border-radius: 8px;
  margin-bottom: 16px;
}

.message {
  margin-bottom: 12px;
}

.message.user {
  text-align: right;
}

.message-content {
  display: inline-block;
  max-width: 80%;
  padding: 12px 16px;
  border-radius: 12px;
  text-align: left;
}

.user-message {
  background: #1a73e8;
  color: white;
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

.loading {
  color: #666;
  font-style: italic;
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
}

.chat-input button:disabled {
  background: #ccc;
  cursor: not-allowed;
}
</style>
