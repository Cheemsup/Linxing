<template>
  <div class="notes-panel">
    <div class="notes-header">
      <h2>笔记管理</h2>
      <div class="header-actions">
        <span class="doc-count">共 {{ total }} 篇文档</span>
        <button @click="loadDocuments" class="btn-refresh" :disabled="loading">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="loading-state">
      <span class="spinner"></span> 加载中...
    </div>

    <div v-else-if="documents.length === 0" class="empty-state">
      <span class="empty-icon">📭</span>
      <p>暂无笔记，请先导入文档。</p>
    </div>

    <div v-else>
      <div class="documents-list">
        <div v-for="doc in documents" :key="doc.id" class="doc-card">
          <div class="doc-icon">{{ getFileIcon(doc.fileType) }}</div>
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
            <button @click="previewDocument(doc)" class="btn-action btn-preview" title="预览">👁 预览</button>
            <button @click="downloadDocument(doc.id)" class="btn-action btn-download" title="下载">⬇ 下载</button>
            <button @click="confirmDelete(doc)" class="btn-action btn-delete" title="删除">🗑 删除</button>
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
        <p>确定要删除文档「{{ deleteTarget?.fileName }}」吗？此操作不可恢复。</p>
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
</template>

<script>
import { documentApi } from '@/api'
import DocumentPreview from './DocumentPreview.vue'

export default {
  name: 'NotesPanel',
  components: {
    DocumentPreview
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
        this.showToast('加载文档列表失败: ' + (error.message || '未知错误'), 'error')
      } finally {
        this.loading = false
      }
    },

    changePage(page) {
      if (page < 1 || page > this.totalPages) return
      this.currentPage = page
      this.loadDocuments()
    },

    async previewDocument(doc) {
      try {
        const response = await documentApi.preview(doc.id)
        const resData = response.data
        if (resData.code === 1) {
          this.previewData = resData.data
          this.showPreview = true
        } else {
          this.showToast('预览失败: ' + (resData.msg || '未知错误'), 'error')
        }
      } catch (error) {
        this.showToast('预览失败: ' + (error.message || '未知错误'), 'error')
      }
    },

    downloadDocument(id) {
      const url = documentApi.getDownloadUrl(id)
      window.open(url, '_blank')
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
        this.showToast('文档已删除', 'success')
        this.showDeleteConfirm = false
        this.deleteTarget = null
        this.loadDocuments()
      } catch (error) {
        this.showToast('删除失败: ' + (error.message || '未知错误'), 'error')
      } finally {
        this.deleting = false
      }
    },

    getFileIcon(fileType) {
      const iconMap = {
        pdf: '📕',
        docx: '📘',
        doc: '📘',
        xlsx: '📗',
        xls: '📗',
        txt: '📄',
        md: '📝'
      }
      return iconMap[fileType] || '📄'
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
.notes-panel {
  padding: 20px;
  position: relative;
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
  border-top-color: #1a73e8;
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
  border-color: #1a73e8;
  box-shadow: 0 2px 8px rgba(26, 115, 232, 0.1);
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

.btn-preview {
  background: #e3f2fd;
  color: #1565c0;
}

.btn-preview:hover {
  background: #bbdefb;
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
</style>
