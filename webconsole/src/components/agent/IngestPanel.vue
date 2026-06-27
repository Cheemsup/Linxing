<template>
  <div class="ingest-panel">
    <h2>导入笔记</h2>
    <p class="hint">上传后将自动整理为可检索的笔记。</p>

    <div class="upload-area" @click="triggerFileInput" @dragover.prevent @drop.prevent="handleDrop">
      <input
        type="file"
        ref="fileInput"
        @change="handleFileSelect"
        accept=".txt,.md,.text,.pdf,.doc,.docx,.xls,.xlsx,.java,.csv,.html,.htm"
        style="display: none"
      />
      <div v-if="!selectedFile" class="upload-placeholder">
        <el-icon class="upload-icon"><Upload /></el-icon>
        <p>点击选择文件或拖拽文件到此处</p>
        <span class="file-types">支持 PDF、Word、Excel、文本、代码、CSV、HTML</span>
      </div>
      <div v-else class="selected-file">
        <el-icon class="file-icon"><component :is="getFileIcon(selectedFile.name)" /></el-icon>
        <span class="file-name">{{ selectedFile.name }}</span>
        <span class="file-size">({{ formatFileSize(selectedFile.size) }})</span>
        <button @click.stop="clearFile" class="clear-btn">
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </div>

    <button @click="uploadFile" :disabled="loading || !selectedFile" class="btn-primary">
      <el-icon v-if="loading" class="is-loading"><Loading /></el-icon>
      <span>{{ loading ? '上传中' : '开始上传' }}</span>
    </button>

    <div v-if="result && !result.success" class="result-box error">
      <el-icon class="result-icon"><CircleCloseFilled /></el-icon>
      <span>{{ result.message }}</span>
    </div>
    <div v-else-if="uploadSuccess" class="result-box success">
      <el-icon class="result-icon"><CircleCheckFilled /></el-icon>
      <div class="success-content">
        <p class="success-title">上传成功，已整理为笔记</p>
        <button class="goto-notes-btn" @click="goToNotes">
          去笔记管理查看<el-icon><ArrowRight /></el-icon>
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { ingestApi } from '@/api/agent/ingest'

const ALLOWED_EXTENSIONS = ['.txt', '.md', '.text', '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.java', '.csv', '.html', '.htm']

export default {
  name: 'IngestPanel',
  data() {
    return {
      selectedFile: null,
      loading: false,
      result: null,
      uploadSuccess: false
    }
  },
  methods: {
    goToNotes() {
      this.$router.push('/notes')
    },
    triggerFileInput() {
      this.$refs.fileInput.click()
    },

    handleFileSelect(event) {
      const file = event.target.files[0]
      if (file) {
        this.validateAndSetFile(file)
      }
    },

    handleDrop(event) {
      const file = event.dataTransfer.files[0]
      if (file) {
        this.validateAndSetFile(file)
      }
    },

    validateAndSetFile(file) {
      const fileName = file.name.toLowerCase()
      const isValidType = ALLOWED_EXTENSIONS.some(type => fileName.endsWith(type))

      if (!isValidType) {
        this.result = {
          success: false,
          message: '不支持的文件格式，请选择 PDF、Word、Excel、文本、代码、CSV 或 HTML 文件'
        }
        return
      }

      this.selectedFile = file
      this.result = null
    },

    clearFile() {
      this.selectedFile = null
      this.$refs.fileInput.value = ''
    },

    async uploadFile() {
      if (!this.selectedFile || this.loading) return

      this.loading = true
      this.result = null
      this.uploadSuccess = false

      try {
        const response = await ingestApi.ingestFile(this.selectedFile)
        const resData = response.data
        if (resData.code === 1) {
          this.uploadSuccess = true
          this.clearFile()
        } else {
          this.result = {
            success: false,
            message: resData.msg || '上传失败，请重试'
          }
        }
      } catch (error) {
        this.result = {
          success: false,
          message: error.response?.data?.message || error.message || '上传失败，请重试'
        }
      } finally {
        this.loading = false
      }
    },

    getFileIcon(fileName) {
      if (!fileName) return 'Document'
      const ext = fileName.split('.').pop().toLowerCase()
      const iconMap = {
        pdf: 'Document',
        doc: 'Document',
        docx: 'Document',
        xls: 'Document',
        xlsx: 'Document',
        txt: 'Document',
        md: 'EditPen',
        text: 'Document',
        java: 'Document',
        csv: 'DataAnalysis',
        html: 'Document',
        htm: 'Document'
      }
      return iconMap[ext] || 'Document'
    },

    formatFileSize(bytes) {
      if (bytes === 0) return '0 B'
      const k = 1024
      const sizes = ['B', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
    }
  }
}
</script>

<style scoped>
.ingest-panel {
  padding: 20px;
  background: white;
  border-radius: 8px;
}

.ingest-panel h2 {
  margin-bottom: 8px;
  color: #333;
}

.hint {
  color: #666;
  font-size: 14px;
  margin-bottom: 24px;
}

.upload-area {
  border: 2px dashed #ddd;
  border-radius: 8px;
  padding: 40px 20px;
  text-align: center;
  cursor: pointer;
  transition: all 0.3s;
  margin-bottom: 16px;
}

.upload-area:hover {
  border-color: #b8763d;
  background: #f8f9fa;
}

.upload-placeholder {
  color: #666;
}

.upload-icon {
  font-size: 48px;
  display: block;
  margin-bottom: 12px;
}

.upload-placeholder p {
  font-size: 14px;
  margin-bottom: 8px;
}

.file-types {
  font-size: 12px;
  color: #999;
}

.selected-file {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #333;
}

.file-icon {
  font-size: 24px;
}

.file-name {
  font-weight: 500;
}

.file-size {
  color: #666;
  font-size: 13px;
}

.clear-btn {
  background: #ffebee;
  color: #c62828;
  border: none;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.clear-btn:hover {
  background: #ffcdd2;
}

.btn-primary {
  width: 100%;
  padding: 12px 24px;
  background: #b8763d;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.btn-primary:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.btn-primary:not(:disabled):hover {
  background: #a0682f;
}

.is-loading {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.result-box {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 6px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.result-icon {
  font-size: 20px;
  flex-shrink: 0;
  margin-top: 1px;
}

.result-box.success {
  background: #e8f5e9;
  color: #2e7d32;
}

.result-box.error {
  background: #ffebee;
  color: #c62828;
}

.success-content {
  flex: 1;
}

.success-title {
  margin: 0 0 8px;
  font-weight: 500;
}

.goto-notes-btn {
  background: #2e7d32;
  color: #fff;
  border: none;
  padding: 6px 14px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: background 0.2s;
}

.goto-notes-btn:hover {
  background: #256528;
}
</style>
