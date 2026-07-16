<template>
  <div class="exam-detail-view">
    <!-- 加载状态 -->
    <div v-if="loading" class="exam-loading">
      <p>加载中...</p>
    </div>

    <!-- 错误提示 -->
    <div v-else-if="fetchError" class="exam-error">
      <p>{{ fetchError }}</p>
      <button class="retry-btn" @click="fetchExam">重试</button>
    </div>

    <!-- 测验详情 -->
    <QuizPanel
      v-else-if="examData"
      :quizData="examData"
      :submitResult="submitResult"
      :draftAnswers="draftAnswers"
      @back="goList"
      @submit="onSubmit"
      @saveDraft="onSaveDraft"
    />
  </div>
</template>

<script>
import QuizPanel from '@/components/agent/QuizPanel.vue'
import { examApi } from '@/api/agent/exam'

export default {
  name: 'ExamDetailView',
  components: {
    QuizPanel
  },
  data() {
    return {
      loading: false,
      fetchError: '',
      examData: null,
      submitResult: null,
      draftAnswers: null
    }
  },
  computed: {
    examId() {
      return this.$route.params.examId
    }
  },
  watch: {
    '$route.params.examId'() {
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
        this.goList()
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
        this.fetchError = '网络错误，请稍后重试'
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

    goList() {
      this.$router.push({ name: 'Quiz' })
    }
  }
}
</script>

<style scoped>
.exam-detail-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fafbfc;
}

.exam-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: #999;
  font-size: 15px;
}

.exam-error {
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
</style>
