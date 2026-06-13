<template>
  <div class="quiz-view">
    <!-- 加载状态 -->
    <div v-if="loading" class="quiz-loading">
      <p>加载中...</p>
    </div>

    <!-- 错误提示 -->
    <div v-else-if="fetchError" class="quiz-error">
      <p>{{ fetchError }}</p>
      <button class="retry-btn" @click="init">重试</button>
    </div>

    <!-- 测验详情 -->
    <QuizPanel
      v-else-if="examData"
      :quizData="examData"
      :submitResult="submitResult"
      :draftAnswers="draftAnswers"
      @submit="onSubmit"
      @saveDraft="onSaveDraft"
    />

    <!-- 测验列表 -->
    <div v-else class="quiz-list">
      <h2 class="list-title">知识测验</h2>
      <div v-if="listLoading" class="quiz-loading">加载中...</div>
      <div v-else-if="examList.length === 0" class="empty-tip">暂无测验记录，可在对话中让助手为您生成测验</div>
      <div v-else class="exam-cards">
        <div
          v-for="exam in examList"
          :key="exam.id"
          class="exam-card"
          @click="goExam(exam.id)"
        >
          <div class="exam-card-title">{{ exam.title }}</div>
          <div class="exam-card-meta">
            <span class="meta-item">{{ exam.questionCount }} 题</span>
            <span class="meta-item" :class="'status-' + exam.status">{{ statusLabel(exam.status) }}</span>
            <span class="meta-item">{{ formatDate(exam.createdAt) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import QuizPanel from '@/components/rag/QuizPanel.vue'
import { examApi } from '@/api/rag/exam'

export default {
  name: 'QuizView',
  components: {
    QuizPanel
  },
  data() {
    return {
      loading: false,
      listLoading: false,
      fetchError: '',
      examData: null,
      submitResult: null,
      draftAnswers: null,
      examList: []
    }
  },
  computed: {
    examId() {
      return this.$route.query.examId
    }
  },
  watch: {
    '$route.query.examId'() {
      this.init()
    }
  },
  mounted() {
    this.init()
  },
  methods: {
    init() {
      this.examData = null
      this.submitResult = null
      this.draftAnswers = null
      this.fetchError = ''
      if (this.examId) {
        this.fetchExam()
      } else {
        this.fetchList()
      }
    },

    async fetchExam() {
      this.loading = true
      try {
        const res = await examApi.getExam(this.examId)
        if (res.data.code === 1) {
          const detail = res.data.data
          this.examData = this.transformExam(detail)
          // 如果测验状态为 in_progress，尝试获取草稿
          if (detail.status === 'in_progress') {
            await this.fetchDraft()
          }
        } else {
          this.fetchError = res.data.msg || '获取测验失败'
        }
      } catch (e) {
        console.error('获取测验失败:', e)
        this.fetchError = '网络错误，获取测验失败'
      } finally {
        this.loading = false
      }
    },

    async fetchDraft() {
      try {
        const res = await examApi.getDraft(this.examId)
        if (res.data.code === 1 && res.data.data) {
          this.draftAnswers = res.data.data
        }
      } catch (e) {
        console.error('获取草稿失败:', e)
      }
    },

    async fetchList() {
      this.listLoading = true
      try {
        const res = await examApi.listExams({ page: 1, size: 50 })
        if (res.data.code === 1) {
          this.examList = res.data.data.records || []
        } else {
          this.fetchError = res.data.msg || '获取测验列表失败'
        }
      } catch (e) {
        console.error('获取测验列表失败:', e)
        this.fetchError = '网络错误，获取测验列表失败'
      } finally {
        this.listLoading = false
      }
    },

    transformExam(detail) {
      return {
        title: detail.title,
        questions: (detail.questions || []).map(q => ({
          id: String(q.id),
          type: q.questionType,
          stem: q.stem,
          options: typeof q.options === 'string' ? JSON.parse(q.options) : (q.options || []),
          answer: '',
          explanation: ''
        }))
      }
    },

    async onSubmit({ answers }) {
      try {
        const res = await examApi.submitAnswer(this.examId, { answers })
        if (res.data.code === 1) {
          this.submitResult = res.data.data
        }
      } catch (e) {
        console.error('提交答案失败:', e)
      }
    },

    async onSaveDraft({ answers }) {
      try {
        const res = await examApi.saveDraft(this.examId, { answers })
        if (res.data.code === 1) {
          this.$message?.success?.('进度已保存') || alert('进度已保存')
        }
      } catch (e) {
        console.error('保存草稿失败:', e)
      }
    },

    goExam(examId) {
      this.$router.push({ path: '/quiz', query: { examId } })
    },

    statusLabel(status) {
      const map = { created: '未作答', in_progress: '作答中', completed: '已完成' }
      return map[status] || status
    },

    formatDate(dt) {
      if (!dt) return ''
      return new Date(dt).toLocaleDateString('zh-CN')
    }
  }
}
</script>

<style scoped>
.quiz-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fafbfc;
}

.quiz-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: #999;
  font-size: 15px;
}

.quiz-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: #d32f2f;
  font-size: 14px;
}

.retry-btn {
  margin-top: 12px;
  padding: 6px 20px;
  border: 1px solid #d32f2f;
  border-radius: 6px;
  background: #fff;
  color: #d32f2f;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.retry-btn:hover {
  background: #d32f2f;
  color: #fff;
}

.quiz-list {
  padding: 24px;
  max-width: 800px;
  margin: 0 auto;
  width: 100%;
  overflow-y: auto;
  height: 100%;
}

.list-title {
  font-size: 20px;
  color: #1a1a1a;
  margin: 0 0 20px;
}

.empty-tip {
  text-align: center;
  color: #999;
  padding: 60px 0;
  font-size: 14px;
}

.exam-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.exam-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 16px 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.exam-card:hover {
  border-color: #1a73e8;
  box-shadow: 0 2px 8px rgba(26, 115, 232, 0.1);
}

.exam-card-title {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  margin-bottom: 8px;
}

.exam-card-meta {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #888;
}

.meta-item {
  display: inline-flex;
  align-items: center;
}

.status-created { color: #1a73e8; }
.status-in_progress { color: #f57f17; }
.status-completed { color: #2e7d32; }
</style>
