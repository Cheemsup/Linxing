<template>
  <div class="exam-list-view">
    <div v-if="listLoading" class="exam-loading">加载中...</div>
    <div v-else-if="fetchError" class="exam-error">
      <p>{{ fetchError }}</p>
      <button class="retry-btn" @click="fetchList">重试</button>
    </div>
    <div v-else-if="examList.length === 0" class="empty-state">
      <el-icon class="empty-icon"><EditPen /></el-icon>
      <p class="empty-title">还没有测验</p>
      <p class="empty-hint">可在对话中告诉助手「根据我的笔记出几道测验题」来生成</p>
    </div>
    <div v-else class="exam-list-body">
      <h2 class="list-title">知识测验</h2>
      <div class="exam-cards">
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
          <div class="exam-card-arrow">
            <el-icon><ArrowRight /></el-icon>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { examApi } from '@/api/agent/exam'

export default {
  name: 'ExamListView',
  data() {
    return {
      listLoading: false,
      fetchError: '',
      examList: []
    }
  },
  mounted() {
    this.fetchList()
  },
  methods: {
    async fetchList() {
      this.listLoading = true
      this.fetchError = ''
      try {
        const res = await examApi.listExams({ page: 1, size: 50 })
        if (res.data.code === 1) {
          this.examList = res.data.data.records || []
        } else {
          this.fetchError = res.data.msg || '获取测验列表失败'
        }
      } catch (e) {
        console.error('获取测验列表失败:', e)
        this.fetchError = '网络错误，请稍后重试'
      } finally {
        this.listLoading = false
      }
    },

    goExam(examId) {
      this.$router.push({ name: 'ExamDetail', params: { examId } })
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
.exam-list-view {
  height: 100%;
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

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #999;
  padding: 40px 20px;
  text-align: center;
}

.empty-icon {
  font-size: 56px;
  color: #cfd8dc;
  margin-bottom: 16px;
}

.empty-title {
  font-size: 16px;
  color: #555;
  margin: 0 0 6px;
}

.empty-hint {
  font-size: 13px;
  color: #999;
  margin: 0;
  line-height: 1.6;
}

.exam-list-body {
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

.exam-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.exam-card {
  position: relative;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 16px 48px 16px 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.exam-card:hover {
  border-color: #b8763d;
  box-shadow: 0 2px 8px rgba(184, 118, 61, 0.1);
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

.exam-card-arrow {
  position: absolute;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  color: #ccc;
  font-size: 16px;
}

.exam-card:hover .exam-card-arrow {
  color: #b8763d;
}

.status-created { color: #b8763d; }
.status-in_progress { color: #f57f17; }
.status-completed { color: #2e7d32; }
</style>
