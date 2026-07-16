<template>
  <div class="chat-home-panel">
    <!-- 页面正中央大号四芒星 logo + 居中输入框，发送后路由跳转到聊天页 -->
    <div class="home-center">
      <svg class="home-logo" viewBox="0 0 48 48" width="140" height="140" aria-hidden="true">
        <path
          d="M24 3 L27.5 20.5 L45 24 L27.5 27.5 L24 45 L20.5 27.5 L3 24 L20.5 20.5 Z"
          fill="none"
          stroke="currentColor"
          stroke-width="1.4"
          stroke-linejoin="round"
          vector-effect="non-scaling-stroke"
        />
      </svg>
      <h1 class="home-title">临星</h1>
      <p class="home-subtitle">从这里开始一次新的对话</p>

      <div class="home-input">
        <textarea
          v-model="question"
          @keydown.enter.exact.prevent="sendQuestion"
          rows="3"
          placeholder="输入你的问题，回车发送……"
        ></textarea>
        <button
          @click="sendQuestion"
          :disabled="sending || !question.trim()"
          :title="sending ? '创建会话中...' : '发送'"
        >
          {{ sending ? '创建会话中...' : '发送' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { chatSessionStore } from '@/stores/agent/chatSessionStore'

export default {
  name: 'ChatHomePanel',
  data() {
    return {
      question: '',
      sending: false
    }
  },
  watch: {
    // 侧栏点"新对话"会清空 activeSessionId，同步清空首页输入框残留
    activeSessionId(val) {
      if (!val) this.question = ''
    }
  },
  computed: {
    activeSessionId() {
      return chatSessionStore.state.activeSessionId
    }
  },
  methods: {
    async sendQuestion() {
      const q = this.question.trim()
      if (!q || this.sending) return
      this.sending = true
      try {
        // 首页只负责建会话 + 透传问题 + 跳转，chatStream 由聊天页 mounted 发起
        const session = await chatSessionStore.createSession('新对话')
        if (!session || !session.id) {
          throw new Error('未返回会话ID')
        }
        chatSessionStore.setPendingQuestion(q)
        // replace 不进历史栈，后退不会回到空首页
        this.$router.replace(`/chat/${session.id}`)
      } catch (e) {
        console.error('创建会话失败:', e)
        alert('创建会话失败: ' + (e.response?.data?.msg || e.message))
        this.sending = false
      }
    }
  }
}
</script>

<style scoped>
.chat-home-panel {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #faf8f4;
}

.home-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  width: 100%;
  max-width: 680px;
  padding: 0 24px;
  /* 视觉重心略偏上，与各大大模型首页一致 */
  transform: translateY(-6vh);
}

.home-logo {
  color: #1a3a32;
  opacity: 0.92;
  flex-shrink: 0;
}

.home-title {
  margin: 0;
  font-family: 'Songti SC', 'STSong', 'Source Han Serif SC', 'Noto Serif CJK SC', 'SimSun', serif;
  font-size: 34px;
  font-weight: 600;
  letter-spacing: 8px;
  color: #1a2e2a;
}

.home-subtitle {
  margin: 0 0 8px;
  font-size: 14px;
  color: #8a948f;
  letter-spacing: 1px;
}

.home-input {
  width: 100%;
  display: flex;
  gap: 12px;
}

.home-input textarea {
  flex: 1;
  padding: 12px;
  border: 1px solid #d9d2c4;
  border-radius: 8px;
  resize: none;
  font-size: 14px;
  font-family: inherit;
  background: #fff;
  box-shadow: 0 1px 3px rgba(26, 46, 42, 0.04);
  transition: border-color 0.2s, box-shadow 0.2s;
}

.home-input textarea:focus {
  outline: none;
  border-color: #b8763d;
  box-shadow: 0 0 0 3px rgba(184, 118, 61, 0.12);
}

.home-input button {
  padding: 12px 24px;
  background: #b8763d;
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-family: inherit;
  white-space: nowrap;
  transition: background 0.2s;
}

.home-input button:hover:not(:disabled) {
  background: #a0682f;
}

.home-input button:disabled {
  background: #ccc;
  cursor: not-allowed;
}
</style>
