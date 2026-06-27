<template>
  <div class="plan-detail-view">
    <!-- 加载状态 -->
    <div v-if="loading" class="sp-loading">
      <p>加载中...</p>
    </div>

    <!-- 错误提示 -->
    <div v-else-if="fetchError" class="sp-error">
      <p>{{ fetchError }}</p>
      <button class="retry-btn" @click="fetchPlan">重试</button>
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
  </div>
</template>

<script>
import StudyPlanTimeline from '@/components/agent/StudyPlanTimeline.vue'
import { studyPlanApi } from '@/api/agent/studyPlan'

export default {
  name: 'PlanDetailView',
  components: {
    StudyPlanTimeline
  },
  data() {
    return {
      loading: false,
      fetchError: '',
      planData: null
    }
  },
  computed: {
    planId() {
      return this.$route.params.planId
    }
  },
  watch: {
    '$route.params.planId'() {
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
        this.goList()
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
        this.fetchError = '网络错误，请稍后重试'
      } finally {
        this.loading = false
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
        let fileName = `学习计划.${format}`
        const disposition = res.headers['content-disposition']
        if (disposition) {
          const match = disposition.match(/filename\*=UTF-8''(.+)/)
          if (match) {
            fileName = decodeURIComponent(match[1])
          }
        }
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

    goList() {
      this.$router.push({ name: 'StudyPlan' })
    }
  }
}
</script>

<style scoped>
.plan-detail-view {
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
</style>
