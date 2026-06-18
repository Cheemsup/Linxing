<template>
  <div class="study-plan-view">
    <!-- 加载状态 -->
    <div v-if="loading" class="sp-loading">
      <p>加载中...</p>
    </div>

    <!-- 错误提示 -->
    <div v-else-if="fetchError" class="sp-error">
      <p>{{ fetchError }}</p>
      <button class="retry-btn" @click="init">重试</button>
    </div>

    <!-- 计划详情（时间线） -->
    <StudyPlanTimeline
      v-else-if="planData"
      :planData="planData"
      @back="goList"
      @status-change="onStatusChange"
      @notes-update="onNotesUpdate"
      @export="onExport"
    />

    <!-- 计划列表 -->
    <div v-else class="plan-list">
      <h2 class="list-title">学习计划</h2>
      <div v-if="listLoading" class="sp-loading">加载中...</div>
      <div v-else-if="planList.length === 0" class="empty-tip">
        暂无学习计划，可在对话中让助手为您制定学习计划
      </div>
      <div v-else class="plan-cards">
        <div
          v-for="plan in planList"
          :key="plan.id"
          class="plan-card"
          @click="goPlan(plan.id)"
        >
          <div class="plan-card-title">{{ plan.title }}</div>
          <div class="plan-card-goal">🎯 {{ plan.goal }}</div>
          <div class="plan-card-meta">
            <span class="meta-item">{{ plan.phaseCount }} 阶段</span>
            <span v-if="plan.duration" class="meta-item">⏱ {{ plan.duration }}</span>
            <span class="meta-item" :class="'status-' + plan.status">{{ statusLabel(plan.status) }}</span>
            <span class="meta-item">{{ formatDate(plan.createdAt) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import StudyPlanTimeline from '@/components/rag/StudyPlanTimeline.vue'
import { studyPlanApi } from '@/api/rag/studyPlan'

export default {
  name: 'StudyPlanView',
  components: {
    StudyPlanTimeline
  },
  data() {
    return {
      loading: false,
      listLoading: false,
      fetchError: '',
      planData: null,
      planList: []
    }
  },
  computed: {
    planId() {
      return this.$route.query.planId
    }
  },
  watch: {
    '$route.query.planId'() {
      this.init()
    }
  },
  mounted() {
    this.init()
  },
  methods: {
    init() {
      this.planData = null
      this.fetchError = ''
      if (this.planId) {
        this.fetchPlan()
      } else {
        this.fetchList()
      }
    },

    async fetchPlan() {
      this.loading = true
      try {
        const res = await studyPlanApi.getPlanDetail(this.planId)
        if (res.data.code === 1) {
          this.planData = res.data.data
        } else {
          this.fetchError = res.data.msg || '获取学习计划失败'
        }
      } catch (e) {
        console.error('获取学习计划失败:', e)
        this.fetchError = '网络错误，获取学习计划失败'
      } finally {
        this.loading = false
      }
    },

    async fetchList() {
      this.listLoading = true
      try {
        const res = await studyPlanApi.listPlans({ page: 1, size: 50 })
        if (res.data.code === 1) {
          this.planList = res.data.data.records || []
        } else {
          this.fetchError = res.data.msg || '获取学习计划列表失败'
        }
      } catch (e) {
        console.error('获取学习计划列表失败:', e)
        this.fetchError = '网络错误，获取学习计划列表失败'
      } finally {
        this.listLoading = false
      }
    },

    async onStatusChange({ phase, newStatus }) {
      try {
        const res = await studyPlanApi.updatePhaseStatus(this.planId, phase.id, {
          status: newStatus
        })
        if (res.data.code === 1) {
          // 刷新计划详情以获取最新进度统计
          await this.fetchPlan()
        }
      } catch (e) {
        console.error('更新阶段状态失败:', e)
      }
    },

    async onNotesUpdate({ phase, notes }) {
      try {
        await studyPlanApi.updatePhaseStatus(this.planId, phase.id, {
          status: phase.progressStatus,
          notes: notes
        })
        // 本地更新，避免整页刷新
        if (this.planData && this.planData.phases) {
          const target = this.planData.phases.find(p => p.id === phase.id)
          if (target) {
            target.notes = notes
          }
        }
      } catch (e) {
        console.error('保存笔记失败:', e)
      }
    },

    async onExport(format) {
      try {
        const res = await studyPlanApi.exportPlan(this.planId, format)
        // 从响应头获取文件名，兜底使用默认名
        let fileName = `学习计划.${format}`
        const disposition = res.headers['content-disposition']
        if (disposition) {
          const match = disposition.match(/filename\*=UTF-8''(.+)/)
          if (match) {
            fileName = decodeURIComponent(match[1])
          }
        }
        // 创建下载链接
        const blob = new Blob([res.data], {
          type: format === 'html' ? 'text/html;charset=utf-8' : 'text/markdown;charset=utf-8'
        })
        const url = window.URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = fileName
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
        window.URL.revokeObjectURL(url)
      } catch (e) {
        console.error('导出失败:', e)
      }
    },

    goPlan(planId) {
      this.$router.push({ path: '/study-plan', query: { planId } })
    },

    goList() {
      this.$router.push({ path: '/study-plan' })
    },

    statusLabel(status) {
      const map = {
        created: '已生成',
        in_progress: '进行中',
        completed: '已完成',
        archived: '已归档'
      }
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
.study-plan-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fafbfc;
}

.sp-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: #999;
  font-size: 15px;
}

.sp-error {
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

.plan-list {
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

.plan-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plan-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 16px 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.plan-card:hover {
  border-color: #1a73e8;
  box-shadow: 0 2px 8px rgba(26, 115, 232, 0.1);
}

.plan-card-title {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  margin-bottom: 6px;
}

.plan-card-goal {
  font-size: 13px;
  color: #666;
  margin-bottom: 8px;
}

.plan-card-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #888;
  flex-wrap: wrap;
}

.meta-item {
  display: inline-flex;
  align-items: center;
}

.status-created { color: #1a73e8; }
.status-in_progress { color: #f57f17; }
.status-completed { color: #2e7d32; }
.status-archived { color: #999; }
</style>
