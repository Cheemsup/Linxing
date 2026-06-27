<template>
  <div class="preview-overlay" @click.self="$emit('close')">
    <div class="preview-container">
      <div class="preview-header">
        <div class="preview-title">
          <el-icon class="file-icon"><component :is="getFileIcon(previewData?.fileType)" /></el-icon>
          <span class="file-name">{{ previewData?.fileName || '文档预览' }}</span>
        </div>
        <div class="preview-toolbar">
          <template v-if="previewData?.previewType === 'pdf'">
            <button @click="zoomOut" class="tool-btn" :disabled="scale <= 0.5" title="缩小">
              <el-icon><ZoomOut /></el-icon>
            </button>
            <span class="zoom-label">{{ Math.round(scale * 100) }}%</span>
            <button @click="zoomIn" class="tool-btn" :disabled="scale >= 2" title="放大">
              <el-icon><ZoomIn /></el-icon>
            </button>
            <button @click="resetZoom" class="tool-btn" title="重置缩放">
              <el-icon><Refresh /></el-icon>
            </button>
          </template>
          <button v-if="previewData?.previewType === 'pdf' && previewData?.pages?.length > 1"
            @click="prevPage" class="tool-btn" :disabled="currentPage <= 1" title="上一页">
            <el-icon><ArrowLeft /></el-icon>
          </button>
          <span v-if="previewData?.previewType === 'pdf' && previewData?.pages?.length > 1"
            class="page-label">{{ currentPage }} / {{ previewData?.pages?.length || 1 }}</span>
          <button v-if="previewData?.previewType === 'pdf' && previewData?.pages?.length > 1"
            @click="nextPage" class="tool-btn" :disabled="currentPage >= (previewData?.pages?.length || 1)" title="下一页">
            <el-icon><ArrowRight /></el-icon>
          </button>
          <button @click="toggleFullscreen" class="tool-btn" title="全屏">
            <el-icon><FullScreen /></el-icon>
          </button>
          <button @click="$emit('close')" class="tool-btn btn-close" title="关闭">
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </div>

      <div class="preview-body" ref="previewBody">
        <div v-if="previewData?.previewType === 'pdf'" class="pdf-viewer" :style="{ transform: `scale(${scale})`, transformOrigin: 'top center' }">
          <div v-for="(page, index) in visiblePages" :key="index" class="pdf-page">
            <div class="page-number">第 {{ currentPage + index }} 页</div>
            <pre class="page-text">{{ page }}</pre>
          </div>
        </div>

        <div v-else-if="previewData?.previewType === 'text'" class="text-viewer" :style="{ transform: `scale(${scale})`, transformOrigin: 'top center' }">
          <pre class="text-content">{{ previewData?.textContent }}</pre>
        </div>

        <div v-else class="unsupported-viewer">
          <el-icon class="unsupported-icon"><Document /></el-icon>
          <p>{{ previewData?.textContent || '该文件类型暂不支持在线预览' }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'DocumentPreview',
  props: {
    previewData: {
      type: Object,
      default: null
    }
  },
  emits: ['close'],
  data() {
    return {
      scale: 1,
      currentPage: 1,
      isFullscreen: false
    }
  },
  computed: {
    visiblePages() {
      if (!this.previewData?.pages) return []
      return [this.previewData.pages[this.currentPage - 1]]
    }
  },
  watch: {
    previewData() {
      this.currentPage = 1
      this.scale = 1
    }
  },
  mounted() {
    document.addEventListener('keydown', this.handleKeydown)
  },
  beforeUnmount() {
    document.removeEventListener('keydown', this.handleKeydown)
    if (this.isFullscreen) {
      document.exitFullscreen()
    }
  },
  methods: {
    handleKeydown(e) {
      if (e.key === 'Escape') {
        this.$emit('close')
      } else if (e.key === 'ArrowLeft') {
        this.prevPage()
      } else if (e.key === 'ArrowRight') {
        this.nextPage()
      } else if (e.key === '+' || e.key === '=') {
        this.zoomIn()
      } else if (e.key === '-') {
        this.zoomOut()
      }
    },

    zoomIn() {
      if (this.scale < 2) {
        this.scale = Math.min(2, this.scale + 0.1)
      }
    },

    zoomOut() {
      if (this.scale > 0.5) {
        this.scale = Math.max(0.5, this.scale - 0.1)
      }
    },

    resetZoom() {
      this.scale = 1
    },

    prevPage() {
      if (this.currentPage > 1) {
        this.currentPage--
        this.$refs.previewBody.scrollTop = 0
      }
    },

    nextPage() {
      const totalPages = this.previewData?.pages?.length || 1
      if (this.currentPage < totalPages) {
        this.currentPage++
        this.$refs.previewBody.scrollTop = 0
      }
    },

    toggleFullscreen() {
      if (!this.isFullscreen) {
        this.$el.querySelector('.preview-container').requestFullscreen()
        this.isFullscreen = true
      } else {
        document.exitFullscreen()
        this.isFullscreen = false
      }
    },

    getFileIcon(fileType) {
      const iconMap = {
        pdf: 'Document',
        docx: 'Document',
        doc: 'Document',
        xlsx: 'Document',
        xls: 'Document',
        txt: 'Document',
        md: 'EditPen'
      }
      return iconMap[fileType] || 'Document'
    }
  }
}
</script>

<style scoped>
.preview-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1002;
  padding: 20px;
}

.preview-container {
  background: white;
  border-radius: 12px;
  width: 100%;
  max-width: 900px;
  height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
  overflow: hidden;
}

.preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  border-bottom: 1px solid #eee;
  background: #fafafa;
  flex-shrink: 0;
}

.preview-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.file-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.file-name {
  font-weight: 500;
  color: #333;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.tool-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #ddd;
  border-radius: 6px;
  background: white;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}

.tool-btn:hover:not(:disabled) {
  background: #f3e6d4;
  border-color: #b8763d;
}

.tool-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-close {
  background: #ffebee;
  border-color: #ef9a9a;
  color: #c62828;
  font-size: 16px;
}

.btn-close:hover {
  background: #ffcdd2 !important;
}

.zoom-label,
.page-label {
  font-size: 13px;
  color: #666;
  min-width: 40px;
  text-align: center;
}

.preview-body {
  flex: 1;
  overflow: auto;
  padding: 20px;
  background: #f5f5f5;
}

.pdf-viewer {
  transition: transform 0.2s;
  margin: 0 auto;
  max-width: 800px;
}

.pdf-page {
  background: white;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 16px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
}

.page-number {
  font-size: 12px;
  color: #999;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eee;
}

.page-text {
  white-space: pre-wrap;
  word-wrap: break-word;
  font-family: 'Segoe UI', 'Microsoft YaHei', sans-serif;
  font-size: 14px;
  line-height: 1.8;
  color: #333;
  margin: 0;
}

.text-viewer {
  transition: transform 0.2s;
  max-width: 800px;
  margin: 0 auto;
}

.text-content {
  background: white;
  border-radius: 8px;
  padding: 24px;
  white-space: pre-wrap;
  word-wrap: break-word;
  font-family: 'Segoe UI', 'Microsoft YaHei', sans-serif;
  font-size: 14px;
  line-height: 1.8;
  color: #333;
  margin: 0;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
}

.unsupported-viewer {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #999;
  text-align: center;
}

.unsupported-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

.unsupported-viewer p {
  font-size: 16px;
}

@media (max-width: 768px) {
  .preview-overlay {
    padding: 10px;
  }

  .preview-container {
    height: 95vh;
    border-radius: 8px;
  }

  .preview-header {
    flex-direction: column;
    gap: 8px;
    padding: 10px 12px;
  }

  .preview-toolbar {
    flex-wrap: wrap;
    justify-content: center;
  }

  .pdf-page,
  .text-content {
    padding: 16px;
  }

  .page-text,
  .text-content {
    font-size: 13px;
    line-height: 1.6;
  }
}
</style>
