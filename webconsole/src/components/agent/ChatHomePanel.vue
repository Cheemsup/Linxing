<template>
  <div class="chat-home-panel">
    <!-- 页面正中央：缓慢旋转的四芒星 + 居中输入框。
         鼠标接近四芒星时，其左移、右侧隐式浮现「记忆」入口。 -->
    <div class="home-center">
      <!-- 感应区：仅包裹四芒星 + 记忆入口，热区收紧到四芒星周围，
           不波及下方标题/输入框，避免误触。 -->
      <div class="home-emblem-row">
        <div class="home-emblem">
          <svg class="home-logo" viewBox="0 0 48 48" width="150" height="150" aria-hidden="true">
            <!-- 外层光晕：hover 时浮现的柔光环 -->
            <circle class="logo-halo" cx="24" cy="24" r="22" />
            <!-- 四芒星本体：与 StarLoader 同源，旋转挂在 svg 上、transform-origin: center -->
            <path
              d="M24 3 L27.5 20.5 L45 24 L27.5 27.5 L24 45 L20.5 27.5 L3 24 L20.5 20.5 Z"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linejoin="round"
              vector-effect="non-scaling-stroke"
            />
          </svg>
        </div>

        <!-- 记忆入口：常态完全隐身，hover 时从右侧星光中淡入浮现 -->
        <router-link
          to="/memory"
          class="memory-entry"
          title="查看与编辑记忆"
        >
          <span class="memory-entry-text">记忆</span>
          <span class="memory-entry-line"></span>
        </router-link>
      </div>

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
  /* 视觉重心略偏上，与各大模型首页一致 */
  transform: translateY(-6vh);
}

/* ============ 四芒星 + 记忆入口 横向容器（感应区） ============ */
/* 热区收紧到四芒星本体周围：width: fit-content，只覆盖四芒星 + 浮现后的按钮宽度。
   不再占满 home-center 全宽，鼠标落在标题/输入框上不会误触发位移。 */
.home-emblem-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0;
  width: fit-content;
  height: 170px;
  position: relative;
  /* 给四芒星四周留一点呼吸感，hover 体验不至于「贴边」才触发 */
  padding: 0 20px;
  transition: transform 0.6s cubic-bezier(0.22, 1, 0.36, 1);
}

.home-emblem-row:hover {
  transform: translateX(-72px);
}

.home-emblem {
  flex-shrink: 0;
  position: relative;
  transition: transform 0.6s cubic-bezier(0.22, 1, 0.36, 1);
}

.home-logo {
  color: #b8763d;
  display: block;
  overflow: visible;
  transform-origin: center;
  animation: star-rotate 10s linear infinite;
}

@keyframes star-rotate {
  from {
    transform: rotate(0deg);
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
  to {
    transform: rotate(360deg);
    opacity: 0.45;
  }
}

/* 光晕：常态不可见，hover 时随四芒星位移一起柔光浮现 */
.logo-halo {
  fill: none;
  stroke: #b8763d;
  stroke-width: 0.5;
  opacity: 0;
  transform-origin: center;
  transition: opacity 0.6s ease 0.1s;
}

.home-emblem-row:hover .logo-halo {
  opacity: 0.35;
  animation: halo-pulse 3s ease-in-out infinite;
}

@keyframes halo-pulse {
  0%, 100% {
    opacity: 0.25;
  }
  50% {
    opacity: 0.5;
  }
}

/* hover 时四芒星略微放大，强化「被注视」的回应感 */
.home-emblem-row:hover .home-emblem {
  transform: scale(1.04);
}

/* ============ 记忆入口（隐式浮现） ============ */
.memory-entry {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-left: 28px;
  padding: 8px 4px;
  text-decoration: none;
  color: #1a2e2a;
  font-size: 14px;
  font-family: 'Songti SC', 'STSong', 'Source Han Serif SC', 'Noto Serif CJK SC', 'SimSun', serif;
  letter-spacing: 6px;
  white-space: nowrap;
  /* 常态完全隐身：不可见、不可点、不占布局视觉 */
  opacity: 0;
  visibility: hidden;
  transform: translateX(-12px);
  transition: opacity 0.5s ease 0.15s, transform 0.5s cubic-bezier(0.22, 1, 0.36, 1) 0.15s, visibility 0s linear 0.5s;
}

.home-emblem-row:hover .memory-entry {
  opacity: 1;
  visibility: visible;
  transform: translateX(0);
  transition: opacity 0.5s ease 0.2s, transform 0.5s cubic-bezier(0.22, 1, 0.36, 1) 0.2s, visibility 0s;
}

/* 记忆文字右侧的细长引导线：从四芒星方向延伸而来，暗示「来自星光的记忆」 */
.memory-entry-line {
  display: inline-block;
  width: 0;
  height: 1px;
  background: linear-gradient(90deg, rgba(184, 118, 61, 0.6), rgba(184, 118, 61, 0));
  transition: width 0.6s cubic-bezier(0.22, 1, 0.36, 1) 0.3s;
}

.home-emblem-row:hover .memory-entry-line {
  width: 48px;
}

.memory-entry:hover .memory-entry-text {
  color: #b8763d;
}

.memory-entry-text {
  transition: color 0.2s;
}

/* ============ 标题 / 副标题 / 输入框 ============ */
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

/* ============ 响应式：窄屏退回纯居中，记忆入口不可达时由侧栏进入 ============ */
@media (max-width: 640px) {
  .home-emblem-row:hover {
    transform: translateX(0);
  }
  .memory-entry {
    display: none;
  }
}
</style>
