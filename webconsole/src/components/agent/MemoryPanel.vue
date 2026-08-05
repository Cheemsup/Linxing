<template>
  <div class="memory-panel">
    <!-- 左侧：文件列表 -->
    <aside class="memory-sidebar">
      <div class="sidebar-header">
        <span class="sidebar-title">记忆</span>
        <button
          class="refresh-btn"
          title="刷新列表"
          @click="loadFiles"
          :disabled="loading"
        >↻</button>
      </div>
      <ul class="file-list" v-loading="loading">
        <li
          v-for="path in files"
          :key="path"
          :class="['file-item', { active: path === currentPath }]"
          @click="selectFile(path)"
          :title="path"
        >
          <span class="file-name">{{ displayName(path) }}</span>
          <span class="file-path">{{ path }}</span>
        </li>
        <li v-if="!loading && files.length === 0" class="empty-tip">
          暂无记忆文件
        </li>
      </ul>
    </aside>

    <!-- 右侧：编辑/预览区 -->
    <section class="memory-content">
      <!-- 空态 -->
      <div v-if="!currentPath" class="content-empty">
        <p>从左侧选择一个记忆文件查看或编辑</p>
      </div>

      <template v-else>
        <!-- 工具栏 -->
        <div class="content-toolbar">
          <span class="toolbar-path" :title="currentPath">{{ currentPath }}</span>
          <div class="toolbar-actions">
            <template v-if="mode === 'view'">
              <button class="btn-text" @click="enterEdit">编辑</button>
            </template>
            <template v-else>
              <span v-if="dirty" class="dirty-dot" title="有未保存的修改">●</span>
              <button class="btn-text" @click="cancelEdit" :disabled="saving">取消</button>
              <button
                class="btn-primary"
                @click="save"
                :disabled="saving || !dirty"
                :title="dirty ? '覆盖式保存' : '无修改'"
              >{{ saving ? '保存中...' : '保存' }}</button>
            </template>
          </div>
        </div>

        <!-- 大文件警告 -->
        <div v-if="mode === 'edit' && editContent.length > 200000" class="warn-bar">
          文件较大（{{ (editContent.length / 1000).toFixed(0) }}K 字符），编辑可能卡顿。
        </div>

        <!-- 预览 -->
        <div v-if="mode === 'view'" class="content-preview markdown-body" v-html="previewHtml"></div>

        <!-- 编辑 -->
        <textarea
          v-else
          v-model="editContent"
          class="content-editor"
          spellcheck="false"
          placeholder="编辑 Markdown 内容..."
        ></textarea>
      </template>
    </section>
  </div>
</template>

<script>
import { ElMessage, ElMessageBox } from 'element-plus'
import { memoryApi } from '@/api/agent/memory'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'

const { renderToHtml } = useMarkdownRenderer()

export default {
  name: 'MemoryPanel',
  data() {
    return {
      files: [],
      currentPath: '',
      rawContent: '',       // 磁盘原文（保存后同步）
      editContent: '',      // 编辑缓冲
      mode: 'view',         // 'view' | 'edit'
      loading: false,
      saving: false
    }
  },
  computed: {
    dirty() {
      return this.editContent !== this.rawContent
    },
    previewHtml() {
      return renderToHtml(this.rawContent)
    }
  },
  mounted() {
    this.loadFiles()
  },
  // 路由离开守卫：未保存时提示
  beforeRouteLeave(to, from, next) {
    if (this.dirty && this.mode === 'edit') {
      ElMessageBox.confirm('有未保存的修改，确定离开？', '提示', {
        confirmButtonText: '离开',
        cancelButtonText: '继续编辑',
        type: 'warning'
      }).then(() => next()).catch(() => next(false))
    } else {
      next()
    }
  },
  methods: {
    async loadFiles() {
      this.loading = true
      try {
        const { data } = await memoryApi.listFiles()
        if (data.code === 1) {
          this.files = data.data || []
        } else {
          ElMessage.error(data.msg || '加载列表失败')
        }
      } catch (e) {
        ElMessage.error('加载列表失败: ' + (e.response?.data?.msg || e.message))
      } finally {
        this.loading = false
      }
    },

    async selectFile(path) {
      if (path === this.currentPath) return
      if (!await this.confirmIfDirty()) return
      this.loading = true
      try {
        const { data } = await memoryApi.readFile(path)
        if (data.code === 1) {
          this.currentPath = path
          this.rawContent = data.data || ''
          this.editContent = this.rawContent
          this.mode = 'view'
        } else {
          ElMessage.error(data.msg || '读取失败')
        }
      } catch (e) {
        ElMessage.error('读取失败: ' + (e.response?.data?.msg || e.message))
      } finally {
        this.loading = false
      }
    },

    enterEdit() {
      this.editContent = this.rawContent
      this.mode = 'edit'
    },

    async cancelEdit() {
      if (!await this.confirmIfDirty()) return
      this.editContent = this.rawContent
      this.mode = 'view'
    },

    async save() {
      if (!this.dirty || this.saving) return
      this.saving = true
      try {
        const { data } = await memoryApi.writeFile(this.currentPath, this.editContent)
        if (data.code === 1) {
          this.rawContent = this.editContent
          this.mode = 'view'
          ElMessage.success('已保存')
        } else {
          ElMessage.error(data.msg || '保存失败')
        }
      } catch (e) {
        ElMessage.error('保存失败: ' + (e.response?.data?.msg || e.message))
      } finally {
        this.saving = false
      }
    },

    // 有未保存修改时弹确认；返回 false 表示用户取消
    async confirmIfDirty() {
      if (!this.dirty || this.mode !== 'edit') return true
      try {
        await ElMessageBox.confirm('有未保存的修改，确定离开？', '提示', {
          confirmButtonText: '放弃修改',
          cancelButtonText: '继续编辑',
          type: 'warning'
        })
        return true
      } catch {
        return false
      }
    },

    // 取路径末段作为主名，完整路径作副名
    displayName(path) {
      const parts = path.split('/')
      return parts[parts.length - 1]
    }
  }
}
</script>

<style scoped>
.memory-panel {
  height: 100%;
  display: flex;
  background: #faf8f4;
  overflow: hidden;
}

/* 左侧文件列表 */
.memory-sidebar {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid #e6dfd0;
  background: #fff;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 10px;
  border-bottom: 1px solid #f0ead9;
}

.sidebar-title {
  font-family: 'Songti SC', 'STSong', 'Source Han Serif SC', 'Noto Serif CJK SC', 'SimSun', serif;
  font-size: 16px;
  font-weight: 600;
  color: #1a2e2a;
  letter-spacing: 2px;
}

.refresh-btn {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 16px;
  color: #8a948f;
  padding: 2px 6px;
  border-radius: 4px;
  transition: color 0.2s, background 0.2s;
}
.refresh-btn:hover:not(:disabled) {
  color: #b8763d;
  background: rgba(184, 118, 61, 0.08);
}
.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.file-list {
  list-style: none;
  margin: 0;
  padding: 8px;
  overflow-y: auto;
  flex: 1;
}

.file-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  transition: background 0.15s;
  border-left: 3px solid transparent;
}
.file-item:hover {
  background: #faf6ee;
}
.file-item.active {
  background: #faf6ee;
  border-left-color: #b8763d;
}

.file-name {
  font-size: 14px;
  color: #1a2e2a;
  font-weight: 500;
}

.file-path {
  font-size: 11px;
  color: #a89e8a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.empty-tip {
  list-style: none;
  text-align: center;
  color: #b0a890;
  font-size: 13px;
  padding: 24px 0;
}

/* 右侧内容区 */
.memory-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.content-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #b0a890;
  font-size: 14px;
}

.content-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid #f0ead9;
  background: #fff;
}

.toolbar-path {
  font-size: 13px;
  color: #5a635e;
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dirty-dot {
  color: #b8763d;
  font-size: 10px;
  margin-right: -4px;
}

.btn-text {
  border: 1px solid #d9d2c4;
  background: #fff;
  color: #1a2e2a;
  padding: 6px 16px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  font-family: inherit;
  transition: border-color 0.2s, color 0.2s;
}
.btn-text:hover:not(:disabled) {
  border-color: #b8763d;
  color: #b8763d;
}
.btn-text:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-primary {
  border: none;
  background: #b8763d;
  color: #fff;
  padding: 6px 18px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  font-family: inherit;
  transition: background 0.2s;
}
.btn-primary:hover:not(:disabled) {
  background: #a0682f;
}
.btn-primary:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.warn-bar {
  padding: 8px 20px;
  background: #fdf3e7;
  color: #a0682f;
  font-size: 12px;
  border-bottom: 1px solid #f0e0c8;
}

.content-preview {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
  background: #fff;
  font-size: 14px;
  line-height: 1.75;
  color: #2a322e;
}

.content-editor {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  padding: 20px 28px;
  font-size: 14px;
  font-family: 'SF Mono', Menlo, Consolas, 'Source Han Serif SC', monospace;
  line-height: 1.7;
  background: #fff;
  color: #2a322e;
}
</style>