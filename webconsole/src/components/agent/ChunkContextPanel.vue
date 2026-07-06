<template>
  <div class="chunk-context-panel">
    <div class="context-header">
      <h3>上下文定位</h3>
      <button @click="$emit('close')" class="btn-close" title="关闭">
        <el-icon><Close /></el-icon>
      </button>
    </div>

    <div v-if="loading" class="context-loading">
      <span class="spinner"></span> 加载中...
    </div>

    <div v-else-if="context" class="context-content">
      <div class="context-section document-info">
        <span class="section-label">所属文档</span>
        <span class="document-name">{{ context.fileName }}</span>
      </div>

      <div v-if="context.parentChunk" class="context-section parent-section">
        <div class="section-header">
          <span class="section-label">父级内容</span>
          <span v-if="context.parentChunk.titlePath" class="parent-title">
            {{ context.parentChunk.titlePath }}
          </span>
        </div>
        <div class="section-body parent-body">
          <RichChunkText
            :chunk-text="context.parentChunk.chunkText"
            :node-metadata="context.parentChunk.nodeMetadata || []"
          />
        </div>
      </div>

      <div class="context-section current-section">
        <div class="section-header">
          <span class="section-label current-label">当前内容</span>
        </div>
        <div class="section-body current-body">
          <RichChunkText
            :chunk-text="context.chunkText"
            :node-metadata="context.nodeMetadata || []"
          />
        </div>
      </div>

      <div v-if="context.siblingChunks && context.siblingChunks.length > 0" class="context-section sibling-section">
        <span class="section-label">同级内容</span>
        <div class="sibling-list">
          <div
            v-for="sibling in context.siblingChunks"
            :key="sibling.chunkId"
            :class="['sibling-item', { active: sibling.chunkId === context.chunkId }]"
            @click="$emit('navigate', sibling.chunkId)"
          >
            <span class="sibling-id">#{{ sibling.chunkId }}</span>
            <span class="sibling-preview">{{ sibling.textPreview }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { chunkApi } from '@/api/agent/chunk'
import RichChunkText from '@/components/agent/RichChunkText.vue'

export default {
  name: 'ChunkContextPanel',
  components: { RichChunkText },
  emits: ['close', 'navigate'],
  data() {
    return {
      context: null,
      loading: false
    }
  },
  methods: {
    async loadContext(chunkId) {
      this.loading = true
      this.context = null
      try {
        const response = await chunkApi.getContext(chunkId)
        const resData = response.data
        if (resData.code === 1) {
          this.context = resData.data
        }
      } catch (error) {
        console.error('加载chunk上下文失败:', error)
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.chunk-context-panel {
  display: flex;
  flex-direction: column;
  background: #fafafa;
  height: 100%;
}

.context-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #eee;
}

.context-header h3 {
  margin: 0;
  font-size: 14px;
  color: #333;
}

.btn-close {
  background: none;
  border: none;
  font-size: 16px;
  color: #999;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
}

.btn-close:hover {
  background: #eee;
  color: #333;
}

.context-loading {
  padding: 40px 20px;
  text-align: center;
  color: #999;
  font-size: 13px;
}

.spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid #ddd;
  border-top-color: #b8763d;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.context-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.context-section {
  margin-bottom: 16px;
}

.section-label {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: #666;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 8px;
}

.current-label {
  color: #b8763d;
}

.document-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.document-name {
  font-size: 14px;
  font-weight: 500;
  color: #333;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.parent-title {
  font-size: 12px;
  color: #a0682f;
  background: #f3e6d4;
  padding: 2px 8px;
  border-radius: 3px;
}

.section-body {
  background: white;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  padding: 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
}

.current-body {
  border-color: #b8763d;
  background: #faf5ed;
}

.parent-body {
  max-height: 150px;
}

.sibling-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sibling-item {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  background: white;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
  font-size: 12px;
}

.sibling-item:hover {
  border-color: #b8763d;
  background: #faf5ed;
}

.sibling-item.active {
  border-color: #b8763d;
  background: #f3e6d4;
}

.sibling-id {
  color: #999;
  flex-shrink: 0;
  font-size: 11px;
}

.sibling-preview {
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
