<template>
  <div class="ingest-panel">
    <h2>导入笔记到知识库</h2>
    <p class="hint">支持导入多种文档格式，系统将自动分块、向量化后存入向量数据库。</p>

    <div class="upload-area" @click="triggerFileInput" @dragover.prevent @drop.prevent="handleDrop">
      <input
        type="file"
        ref="fileInput"
        @change="handleFileSelect"
        accept=".txt,.md,.text,.pdf,.doc,.docx,.xls,.xlsx,.java,.csv,.html,.htm"
        style="display: none"
      />
      <div v-if="!selectedFile" class="upload-placeholder">
        <span class="upload-icon">📄</span>
        <p>点击选择文件或拖拽文件到此处</p>
        <span class="file-types">支持 PDF、Word、Excel、文本、代码、CSV、HTML</span>
      </div>
      <div v-else class="selected-file">
        <span class="file-icon">{{ getFileIcon(selectedFile.name) }}</span>
        <span class="file-name">{{ selectedFile.name }}</span>
        <span class="file-size">({{ formatFileSize(selectedFile.size) }})</span>
        <button @click.stop="clearFile" class="clear-btn">×</button>
      </div>
    </div>

    <button @click="uploadFile" :disabled="loading || !selectedFile" class="btn-primary">
      {{ loading ? '上传中...' : '开始上传' }}
    </button>

    <div v-if="result" :class="['result-box', result.success ? 'success' : 'error']">
      <strong>{{ result.success ? '成功' : '失败' }}:</strong> {{ result.message }}
    </div>
  </div>
</template>

<script>
import { ragApi } from '@/api'

const ALLOWED_EXTENSIONS = ['.txt', '.md', '.text', '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.java', '.csv', '.html', '.htm']

export default {
  name: 'IngestPanel',
  data() {
    return {
      selectedFile: null,
      loading: false,
      result: null
    }
  },
  methods: {
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

      try {
        const response = await ragApi.ingestFile(this.selectedFile)
        const resData = response.data
        if (resData.code === 1) {
          const data = resData.data || {}
          this.result = {
            success: true,
            message: data.message || '文件上传成功'
          }
          if (this.result.success) {
            this.clearFile()
          }
        } else {
          this.result = {
            success: false,
            message: resData.msg || '文件上传失败'
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
      if (!fileName) return '📄'
      const ext = fileName.split('.').pop().toLowerCase()
      const iconMap = {
        pdf: '📕',
        doc: '📘',
        docx: '📘',
        xls: '📗',
        xlsx: '📗',
        txt: '📄',
        md: '📝',
        text: '📄',
        java: '☕',
        csv: '📊',
        html: '🌐',
        htm: '🌐'
      }
      return iconMap[ext] || '📄'
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
  border-color: #1a73e8;
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
  background: #1a73e8;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.btn-primary:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.btn-primary:not(:disabled):hover {
  background: #1557b0;
}

.result-box {
  margin-top: 16px;
  padding: 12px 16px;
  border-radius: 6px;
}

.result-box.success {
  background: #e8f5e9;
  color: #2e7d32;
}

.result-box.error {
  background: #ffebee;
  color: #c62828;
}
</style>
