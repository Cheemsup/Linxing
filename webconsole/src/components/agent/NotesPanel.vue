<template>
  <div class="notes-panel-wrapper">
    <div class="notes-panel">
      <div class="notes-header">
        <h2>笔记管理</h2>
        <div class="header-actions">
          <span class="doc-count">共 {{ total }} 篇笔记</span>
          <button @click="loadDocuments" class="btn-refresh" :disabled="loading">刷新</button>
        </div>
      </div>

      <div v-if="loading" class="loading-state">
        <span class="spinner"></span> 加载中...
      </div>

      <div v-else-if="documents.length === 0" class="empty-state">
        <el-icon class="empty-icon"><FolderOpened /></el-icon>
        <p>暂无笔记，请先去导入页上传。</p>
      </div>

      <div v-else>
        <div class="documents-list">
          <div v-for="doc in documents" :key="doc.id" class="doc-card">
            <div class="doc-icon"><el-icon><component :is="getFileIcon(doc.fileType)" /></el-icon></div>
            <div class="doc-info">
              <div class="doc-name" :title="doc.fileName">{{ doc.fileName }}</div>
              <div class="doc-meta">
                <span class="meta-item">
                  <span class="meta-label">类型:</span>
                  {{ getFileTypeLabel(doc.fileType) }}
                </span>
                <span class="meta-item">
                  <span class="meta-label">大小:</span>
                  {{ formatFileSize(doc.fileSize) }}
                </span>
                <span class="meta-item">
                  <span class="meta-label">上传:</span>
                  {{ formatDate(doc.createdAt) }}
                </span>
                <span :class="['status-badge', doc.status]">
                  {{ getStatusLabel(doc.status) }}
                </span>
              </div>
            </div>
            <div class="doc-actions">
              <button @click="openTreeNav(doc)" class="btn-action btn-tree" title="查看笔记目录">
                <el-icon><Connection /></el-icon><span>目录</span>
              </button>
              <button @click="previewDocument(doc)" class="btn-action btn-preview" title="预览">
                <el-icon><View /></el-icon><span>预览</span>
              </button>
              <button @click="downloadDocument(doc.id)" class="btn-action btn-download" title="下载">
                <el-icon><Download /></el-icon><span>下载</span>
              </button>
              <button @click="confirmDelete(doc)" class="btn-action btn-delete" title="删除">
                <el-icon><Delete /></el-icon><span>删除</span>
              </button>
            </div>
          </div>
        </div>

        <div v-if="totalPages > 1" class="pagination">
          <button
            @click="changePage(currentPage - 1)"
            :disabled="currentPage <= 1"
            class="page-btn"
          >上一页</button>
          <span class="page-info">{{ currentPage }} / {{ totalPages }}</span>
          <button
            @click="changePage(currentPage + 1)"
            :disabled="currentPage >= totalPages"
            class="page-btn"
          >下一页</button>
        </div>
      </div>

      <div v-if="toast.show" :class="['toast', toast.type]">
        {{ toast.message }}
      </div>

      <div v-if="showDeleteConfirm" class="modal-overlay" @click.self="showDeleteConfirm = false">
        <div class="modal-box">
          <h3>确认删除</h3>
          <p>确定要删除笔记「{{ deleteTarget?.fileName }}」吗？此操作不可恢复。</p>
          <div class="modal-actions">
            <button @click="showDeleteConfirm = false" class="btn-cancel">取消</button>
            <button @click="executeDelete" class="btn-confirm-delete" :disabled="deleting">
              {{ deleting ? '删除中...' : '确认删除' }}
            </button>
          </div>
        </div>
      </div>

      <DocumentPreview
        v-if="showPreview"
        :previewData="previewData"
        @close="showPreview = false"
      />
    </div>

    <ChunkTreeNav
      v-if="showTreeNav"
      :document-id="selectedDoc.id"
      :file-name="selectedDoc.fileName"
      @close="showTreeNav = false"
      @select="handleChunkSelect"
    />

    <div v-if="selectedChunkPreview" class="chunk-preview-overlay" @click.self="selectedChunkPreview = null">
      <div class="chunk-preview-box">
        <div class="chunk-preview-header">
          <h3>内容预览</h3>
          <button @click="selectedChunkPreview = null" class="btn-close-preview">
            <el-icon><Close /></el-icon>
          </button>
        </div>
        <div class="chunk-preview-body">
          <div v-if="selectedChunkPreview.titlePath" class="chunk-preview-title">
            {{ selectedChunkPreview.titlePath }}
          </div>
          <div class="chunk-preview-text">{{ selectedChunkPreview.textPreview }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { documentApi } from '@/api/agent/document'
import DocumentPreview from './DocumentPreview.vue'
import ChunkTreeNav from './ChunkTreeNav.vue'

export default {
  name: 'NotesPanel',
  components: {
    DocumentPreview,
    ChunkTreeNav
  },
  data() {
    return {
      documents: [],
      loading: false,
      currentPage: 1,
      pageSize: 10,
      total: 0,
      totalPages: 1,
      showDeleteConfirm: false,
      deleteTarget: null,
      deleting: false,
      showPreview: false,
      previewData: null,
      showTreeNav: false,
      selectedDoc: {},
      selectedChunkPreview: null,
      toast: {
        show: false,
        message: '',
        type: 'success'
      }
    }
  },
  mounted() {
    this.loadDocuments()
  },
  methods: {
    async loadDocuments() {
      this.loading = true
      try {
        const response = await documentApi.list(this.currentPage, this.pageSize)
        const resData = response.data
        if (resData.code === 1) {
          const pageData = resData.data
          this.documents = pageData.records || []
          this.total = pageData.total || 0
          this.currentPage = pageData.page || 1
          this.totalPages = pageData.totalPages || 1
        }
      } catch (error) {
        this.showToast('加载笔记失败：' + (error.message || '未知错误'), 'error')
      } finally {
        this.loading = false
      }
    },

    changePage(page) {
      if (page < 1 || page > this.totalPages) return
      this.currentPage = page
      this.loadDocuments()
    },

    openTreeNav(doc) {
      this.selectedDoc = doc
      this.showTreeNav = true
    },

    handleChunkSelect(node) {
      this.selectedChunkPreview = node
    },

    async previewDocument(doc) {
      try {
        const response = await documentApi.preview(doc.id)
        const resData = response.data
        if (resData.code === 1) {
          this.previewData = resData.data
          this.showPreview = true
        } else {
          this.showToast('预览失败：' + (resData.msg || '未知错误'), 'error')
        }
      } catch (error) {
        this.showToast('预览失败：' + (error.message || '未知错误'), 'error')
      }
    },

    async downloadDocument(id) {
      try {
        const doc = this.documents.find(d => d.id === id)
        const response = await documentApi.download(id)
        const blob = response.data
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = doc ? doc.fileName : 'download'
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        URL.revokeObjectURL(url)
      } catch (error) {
        this.showToast('下载失败：' + (error.message || '未知错误'), 'error')
      }
    },

    confirmDelete(doc) {
      this.deleteTarget = doc
      this.showDeleteConfirm = true
    },

    async executeDelete() {
      if (!this.deleteTarget || this.deleting) return
      this.deleting = true
      try {
        await documentApi.delete(this.deleteTarget.id)
        this.showToast('笔记已删除', 'success')
        this.showDeleteConfirm = false
        this.deleteTarget = null
        this.loadDocuments()
      } catch (error) {
        this.showToast('删除失败：' + (error.message || '未知错误'), 'error')
      } finally {
        this.deleting = false
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
    },

    getFileTypeLabel(fileType) {
      const labelMap = {
        pdf: 'PDF',
        docx: 'Word',
        doc: 'Word',
        xlsx: 'Excel',
        xls: 'Excel',
        txt: '文本',
        md: 'Markdown'
      }
      return labelMap[fileType] || fileType || '未知'
    },

    getStatusLabel(status) {
      const labelMap = {
        processing: '处理中',
        completed: '已完成',
        failed: '失败'
      }
      return labelMap[status] || status || '未知'
    },

    formatFileSize(bytes) {
      if (!bytes || bytes === 0) return '0 B'
      const k = 1024
      const sizes = ['B', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
    },

    formatDate(dateStr) {
      if (!dateStr) return ''
      const date = new Date(dateStr)
      return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      })
    },

    showToast(message, type = 'success') {
      this.toast = { show: true, message, type }
      setTimeout(() => {
        this.toast.show = false
      }, 3000)
    }
  }
}
</script>

<style scoped>
.notes-panel-wrapper {
  display: flex;
  height: 100%;
}

.notes-panel {
  flex: 1;
  padding: 20px;
  position: relative;
  overflow-y: auto;
}

.notes-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.notes-header h2 {
  margin: 0;
  color: #333;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.doc-count {
  color: #666;
  font-size: 14px;
}

.btn-refresh {
  padding: 8px 16px;
  background: #f5f5f5;
  border: 1px solid #ddd;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.btn-refresh:hover:not(:disabled) {
  background: #e0e0e0;
}

.btn-refresh:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.loading-state,
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #666;
}

.empty-icon {
  font-size: 48px;
  display: block;
  margin-bottom: 12px;
}

.spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid #ddd;
  border-top-color: #b8763d;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.documents-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.doc-card {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #fafafa;
  border: 1px solid #eee;
  border-radius: 8px;
  padding: 16px;
  transition: all 0.2s;
}

.doc-card:hover {
  border-color: #b8763d;
  box-shadow: 0 2px 8px rgba(184, 118, 61, 0.1);
}

.doc-icon {
  font-size: 32px;
  flex-shrink: 0;
}

.doc-info {
  flex: 1;
  min-width: 0;
}

.doc-name {
  font-weight: 500;
  color: #333;
  font-size: 15px;
  margin-bottom: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: #888;
  align-items: center;
}

.meta-label {
  color: #aaa;
}

.status-badge {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
}

.status-badge.completed {
  background: #e8f5e9;
  color: #2e7d32;
}

.status-badge.processing {
  background: #fff3e0;
  color: #e65100;
}

.status-badge.failed {
  background: #ffebee;
  color: #c62828;
}

.doc-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.btn-action {
  padding: 6px 12px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.2s;
}

.btn-tree {
  background: #e8f5e9;
  color: #2e7d32;
}

.btn-tree:hover {
  background: #c8e6c9;
}

.btn-preview {
  background: #f3e6d4;
  color: #a0682f;
}

.btn-preview:hover {
  background: #ecd9b8;
}

.btn-download {
  background: #f3e5f5;
  color: #7b1fa2;
}

.btn-download:hover {
  background: #e1bee7;
}

.btn-delete {
  background: #ffebee;
  color: #c62828;
}

.btn-delete:hover {
  background: #ffcdd2;
}

.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #eee;
}

.page-btn {
  padding: 8px 16px;
  background: #f5f5f5;
  border: 1px solid #ddd;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.page-btn:hover:not(:disabled) {
  background: #e0e0e0;
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-info {
  color: #666;
  font-size: 14px;
}

.toast {
  position: fixed;
  top: 20px;
  right: 20px;
  padding: 12px 24px;
  border-radius: 6px;
  font-size: 14px;
  z-index: 1000;
  animation: slideIn 0.3s ease;
}

.toast.success {
  background: #e8f5e9;
  color: #2e7d32;
  border: 1px solid #a5d6a7;
}

.toast.error {
  background: #ffebee;
  color: #c62828;
  border: 1px solid #ef9a9a;
}

@keyframes slideIn {
  from { transform: translateX(100%); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
}

.modal-box {
  background: white;
  border-radius: 12px;
  padding: 24px;
  max-width: 420px;
  width: 90%;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
}

.modal-box h3 {
  margin: 0 0 12px;
  color: #333;
}

.modal-box p {
  color: #666;
  margin-bottom: 20px;
  line-height: 1.5;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.btn-cancel {
  padding: 8px 20px;
  background: #f5f5f5;
  border: 1px solid #ddd;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.btn-cancel:hover {
  background: #e0e0e0;
}

.btn-confirm-delete {
  padding: 8px 20px;
  background: #c62828;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.btn-confirm-delete:hover:not(:disabled) {
  background: #b71c1c;
}

.btn-confirm-delete:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.chunk-preview-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1002;
}

.chunk-preview-box {
  background: white;
  border-radius: 12px;
  padding: 24px;
  max-width: 600px;
  width: 90%;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
}

.chunk-preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.chunk-preview-header h3 {
  margin: 0;
  color: #333;
}

.btn-close-preview {
  background: none;
  border: none;
  font-size: 16px;
  color: #999;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
}

.btn-close-preview:hover {
  background: #eee;
  color: #333;
}

.chunk-preview-body {
  overflow-y: auto;
  flex: 1;
}

.chunk-preview-title {
  font-weight: 600;
  color: #a0682f;
  margin-bottom: 12px;
  font-size: 14px;
}

.chunk-preview-text {
  font-size: 14px;
  line-height: 1.7;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
