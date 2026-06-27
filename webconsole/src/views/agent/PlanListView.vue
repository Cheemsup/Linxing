<template>
  <div class="plan-list-view">
    <div v-if="listLoading" class="sp-loading">加载中...</div>
    <div v-else-if="fetchError" class="sp-error">
      <p>{{ fetchError }}</p>
      <button class="retry-btn" @click="fetchList">重试</button>
    </div>
    <div v-else-if="planList.length === 0" class="empty-state">
      <el-icon class="empty-icon"><Calendar /></el-icon>
      <p class="empty-title">还没有学习计划</p>
      <p class="empty-hint">可在对话中告诉助手「帮我制定一个学习 XXX 的计划」来生成</p>
    </div>
    <div v-else class="plan-list-body">
      <h2 class="list-title">学习计划</h2>
      <div class="plan-cards">
        <div
          v-for="plan in planList"
          :key="plan.id"
          class="plan-card"
          @click="goPlan(plan.id)"
        >
          <div class="plan-card-title">{{ plan.title }}</div>
          <div class="plan-card-goal">
            <el-icon><Aim /></el-icon><span>{{ plan.goal }}</span>
          </div>
          <div class="plan-card-meta">
            <span class="meta-item">{{ plan.phaseCount }} 阶段</span>
            <span v-if="plan.duration" class="meta-item">
              <el-icon><Timer /></el-icon><span>{{ plan.duration }}</span>
            </span>
            <span class="meta-item" :class="'status-' + plan.status">{{ statusLabel(plan.status) }}</span>
            <span class="meta-item">{{ formatDate(plan.createdAt) }}</span>
          </div>
          <div class="plan-card-arrow">
            <el-icon><ArrowRight /></el-icon>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { studyPlanApi } from '@/api/agent/studyPlan'

export default {
  name: 'PlanListView',
  data() {
    return {
      listLoading: false,
      fetchError: '',
      planList: []
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
        const res = await studyPlanApi.listPlans({ page: 1, size: 50 })
        if (res.data.code === 1) {
          this.planList = res.data.data.records || []
        } else {
          this.fetchError = res.data.msg || '获取学习计划列表失败'
        }
      } catch (e) {
        console.error('获取学习计划列表失败:', e)
        this.fetchError = '网络错误，请稍后重试'
      } finally {
        this.listLoading = false
      }
    },

    goPlan(planId) {
      this.$router.push({ name: 'StudyPlanDetail', params: { planId } })
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
.plan-list-view {
  height: 100%;
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

.plan-list-body {
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

.plan-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plan-card {
  position: relative;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 16px 48px 16px 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.plan-card:hover {
  border-color: #b8763d;
  box-shadow: 0 2px 8px rgba(184, 118, 61, 0.1);
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
  display: flex;
  align-items: center;
  gap: 6px;
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
  gap: 4px;
}

.plan-card-arrow {
  position: absolute;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  color: #ccc;
  font-size: 16px;
}

.plan-card:hover .plan-card-arrow {
  color: #b8763d;
}

.status-created { color: #b8763d; }
.status-in_progress { color: #f57f17; }
.status-completed { color: #2e7d32; }
.status-archived { color: #999; }
</style>
