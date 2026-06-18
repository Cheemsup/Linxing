<template>
  <div class="study-plan-timeline">
    <!-- 计划头部 -->
    <div class="plan-header">
      <div class="plan-header-main">
        <h2 class="plan-title">{{ planData.title }}</h2>
        <div class="plan-meta">
          <span class="meta-item">🎯 {{ planData.goal }}</span>
          <span v-if="planData.duration" class="meta-item">⏱ {{ planData.duration }}</span>
          <span class="meta-item" :class="'status-' + planData.status">{{ statusLabel(planData.status) }}</span>
        </div>
        <p v-if="planData.description" class="plan-desc">{{ planData.description }}</p>
      </div>
      <div class="plan-header-actions">
        <button class="export-btn" @click="onExport('md')" title="导出为 Markdown">📄 导出 MD</button>
        <button class="export-btn" @click="onExport('html')" title="导出为 HTML">🌐 导出 HTML</button>
        <button class="back-btn" @click="$emit('back')">← 返回列表</button>
      </div>
    </div>

    <!-- 进度条 -->
    <div v-if="planData.progress" class="progress-section">
      <div class="progress-info">
        <span>进度：{{ planData.progress.completedPhases }}/{{ planData.progress.totalPhases }} 阶段已完成</span>
        <span class="progress-percentage">{{ planData.progress.completionPercentage }}%</span>
      </div>
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: planData.progress.completionPercentage + '%' }"></div>
      </div>
      <div class="progress-stats">
        <span class="stat-completed">✓ 已完成 {{ planData.progress.completedPhases }}</span>
        <span class="stat-in-progress">◐ 进行中 {{ planData.progress.inProgressPhases }}</span>
        <span class="stat-not-started">○ 未开始 {{ planData.progress.notStartedPhases }}</span>
      </div>
    </div>

    <!-- 阶段时间线 -->
    <div class="timeline">
      <div
        v-for="phase in planData.phases"
        :key="phase.id"
        :class="['timeline-item', 'phase-' + phase.progressStatus]"
      >
        <div class="timeline-marker">
          <span v-if="phase.progressStatus === 'completed'" class="marker-icon completed">✓</span>
          <span v-else-if="phase.progressStatus === 'in_progress'" class="marker-icon in-progress">◐</span>
          <span v-else class="marker-icon not-started">{{ phase.phaseOrder }}</span>
        </div>

        <div class="timeline-content">
          <div class="phase-header">
            <h3 class="phase-title">
              <span class="phase-order">阶段 {{ phase.phaseOrder }}</span>
              {{ phase.title }}
            </h3>
            <div class="phase-actions">
              <select
                :value="phase.progressStatus"
                class="status-select"
                @change="onStatusChange(phase, $event.target.value)"
              >
                <option value="not_started">○ 未开始</option>
                <option value="in_progress">◐ 进行中</option>
                <option value="completed">✓ 已完成</option>
              </select>
            </div>
          </div>

          <div class="phase-body">
            <div v-if="phase.duration" class="phase-field">
              <span class="field-label">⏱ 时长：</span>
              <span>{{ phase.duration }}</span>
            </div>
            <div v-if="phase.objective" class="phase-field">
              <span class="field-label">🎯 目标：</span>
              <span>{{ phase.objective }}</span>
            </div>

            <div v-if="parseArray(phase.keyTopics).length" class="phase-field">
              <span class="field-label">📚 关键知识点：</span>
              <ul class="field-list">
                <li v-for="(item, i) in parseArray(phase.keyTopics)" :key="i">{{ item }}</li>
              </ul>
            </div>

            <div v-if="parseArray(phase.resources).length" class="phase-field">
              <span class="field-label">🔗 学习资源：</span>
              <ul class="field-list">
                <li v-for="(item, i) in parseArray(phase.resources)" :key="i">
                  <template v-if="typeof item === 'object' && item.url">
                    <a :href="item.url" target="_blank" rel="noopener">{{ item.name || item.url }}</a>
                  </template>
                  <template v-else>{{ item }}</template>
                </li>
              </ul>
            </div>

            <div v-if="parseArray(phase.practiceTasks).length" class="phase-field">
              <span class="field-label">✏️ 实践任务：</span>
              <ul class="field-list">
                <li v-for="(item, i) in parseArray(phase.practiceTasks)" :key="i">{{ item }}</li>
              </ul>
            </div>

            <div v-if="parseArray(phase.milestones).length" class="phase-field">
              <span class="field-label">🏆 里程碑：</span>
              <ul class="field-list">
                <li v-for="(item, i) in parseArray(phase.milestones)" :key="i">{{ item }}</li>
              </ul>
            </div>

            <div class="phase-notes">
              <textarea
                class="notes-input"
                placeholder="记录学习笔记..."
                :value="phase.notes || ''"
                @blur="onNotesBlur(phase, $event.target.value)"
              ></textarea>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'StudyPlanTimeline',
  props: {
    planData: {
      type: Object,
      required: true
    }
  },
  emits: ['back', 'status-change', 'notes-update', 'export'],
  methods: {
    statusLabel(status) {
      const map = {
        created: '已生成',
        in_progress: '进行中',
        completed: '已完成',
        archived: '已归档'
      }
      return map[status] || status
    },

    parseArray(jsonStr) {
      if (!jsonStr) return []
      if (Array.isArray(jsonStr)) return jsonStr
      try {
        const parsed = JSON.parse(jsonStr)
        return Array.isArray(parsed) ? parsed : []
      } catch (e) {
        return []
      }
    },

    onStatusChange(phase, newStatus) {
      if (newStatus === phase.progressStatus) return
      this.$emit('status-change', { phase, newStatus })
    },

    onNotesBlur(phase, notes) {
      const trimmed = notes.trim()
      if (trimmed === (phase.notes || '')) return
      this.$emit('notes-update', { phase, notes: trimmed })
    },

    onExport(format) {
      this.$emit('export', format)
    }
  }
}
</script>

<style scoped>
.study-plan-timeline {
  padding: 24px;
  max-width: 900px;
  margin: 0 auto;
  width: 100%;
  overflow-y: auto;
  height: 100%;
}

.plan-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
  gap: 16px;
}

.plan-header-main {
  flex: 1;
}

.plan-title {
  font-size: 22px;
  color: #1a1a1a;
  margin: 0 0 12px;
}

.plan-meta {
  display: flex;
  gap: 16px;
  font-size: 14px;
  color: #666;
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

.plan-desc {
  margin-top: 12px;
  color: #555;
  font-size: 14px;
  line-height: 1.6;
}

.plan-header-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.export-btn, .back-btn {
  padding: 6px 14px;
  border: 1px solid #ddd;
  border-radius: 6px;
  background: #fff;
  color: #666;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
  white-space: nowrap;
}

.export-btn:hover {
  border-color: #1a73e8;
  color: #1a73e8;
}

.back-btn:hover {
  border-color: #666;
  color: #333;
}

.progress-section {
  background: #f5f8ff;
  border-radius: 10px;
  padding: 16px 20px;
  margin-bottom: 24px;
}

.progress-info {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: #333;
  margin-bottom: 8px;
}

.progress-percentage {
  font-weight: 600;
  color: #1a73e8;
}

.progress-bar {
  background: #e0e0e0;
  border-radius: 4px;
  height: 8px;
  overflow: hidden;
  margin-bottom: 8px;
}

.progress-fill {
  background: linear-gradient(90deg, #1a73e8, #4285f4);
  height: 100%;
  transition: width 0.3s ease;
}

.progress-stats {
  display: flex;
  gap: 16px;
  font-size: 12px;
}

.stat-completed { color: #2e7d32; }
.stat-in-progress { color: #f57f17; }
.stat-not-started { color: #999; }

.timeline {
  position: relative;
}

.timeline-item {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
  position: relative;
}

.timeline-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 15px;
  top: 32px;
  bottom: -24px;
  width: 2px;
  background: #e0e0e0;
}

.timeline-marker {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  z-index: 1;
}

.marker-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 14px;
  font-weight: 600;
}

.marker-icon.completed {
  background: #e6f4ea;
  color: #2e7d32;
  border: 2px solid #2e7d32;
}

.marker-icon.in-progress {
  background: #fef7e0;
  color: #f57f17;
  border: 2px solid #f57f17;
}

.marker-icon.not-started {
  background: #f1f3f4;
  color: #5f6368;
  border: 2px solid #dadce0;
}

.timeline-content {
  flex: 1;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 16px 20px;
  transition: box-shadow 0.2s;
}

.phase-completed .timeline-content {
  border-left: 3px solid #2e7d32;
}

.phase-in_progress .timeline-content {
  border-left: 3px solid #f57f17;
}

.timeline-content:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.phase-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.phase-title {
  font-size: 16px;
  color: #1a1a1a;
  margin: 0;
}

.phase-order {
  color: #1a73e8;
  font-size: 13px;
  margin-right: 8px;
}

.status-select {
  padding: 4px 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  background: #fff;
}

.status-select:hover {
  border-color: #1a73e8;
}

.phase-body {
  font-size: 14px;
  color: #555;
  line-height: 1.6;
}

.phase-field {
  margin-bottom: 10px;
}

.field-label {
  font-weight: 600;
  color: #333;
}

.field-list {
  margin: 4px 0 0;
  padding-left: 20px;
}

.field-list li {
  margin: 2px 0;
}

.field-list a {
  color: #1a73e8;
  text-decoration: none;
}

.field-list a:hover {
  text-decoration: underline;
}

.phase-notes {
  margin-top: 12px;
}

.notes-input {
  width: 100%;
  min-height: 40px;
  padding: 8px 10px;
  border: 1px dashed #ccc;
  border-radius: 6px;
  font-size: 13px;
  color: #555;
  resize: vertical;
  font-family: inherit;
  background: #fafafa;
}

.notes-input:focus {
  outline: none;
  border-color: #1a73e8;
  background: #fff;
}
</style>
