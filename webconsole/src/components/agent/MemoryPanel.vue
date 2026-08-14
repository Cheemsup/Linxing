<template>
  <div class="memory-panel">
    <!-- 左侧：文件列表（树形） -->
    <aside class="memory-sidebar">
      <div class="sidebar-header">
        <span class="sidebar-title">记忆</span>
        <div class="header-actions">
          <button
            class="btn-text sidebar-action-btn"
            title="一键重建核心模板（Agent.md / User.md / Directory.md），Current 与 History 不受影响"
            @click="rebuildTemplates"
            :disabled="rebuilding"
          >重建</button>
          <button
            class="btn-text sidebar-action-btn"
            title="刷新列表"
            @click="loadFiles"
            :disabled="loading"
          >刷新</button>
        </div>
      </div>
      <ul class="file-list" v-loading="loading">
        <MemoryTreeNode
          v-for="node in fileTree"
          :key="node.path"
          :node="node"
          :depth="0"
          :current-path="currentPath"
          @select="selectFile"
        />
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
import { h } from 'vue'
import { memoryApi } from '@/api/agent/memory'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'

const { renderToHtml } = useMarkdownRenderer()

/**
 * 记忆文件树节点：递归渲染文件夹/文件。
 * 文件夹点击切换展开（默认折叠）；文件点击抛 select 事件由父组件加载内容。
 * 注：项目为 runtime-only Vue，不能在内联子组件用 template 字符串，故用 render 函数。
 */
const MemoryTreeNode = {
  name: 'MemoryTreeNode',
  props: {
    node: { type: Object, required: true },
    depth: { type: Number, default: 0 },
    currentPath: { type: String, default: '' }
  },
  emits: ['select'],
  data() {
    return { expanded: false }
  },
  computed: {
    isDir() {
      return this.node.type === 'dir'
    },
    isActive() {
      return !this.isDir && this.node.path === this.currentPath
    },
    isTemplate() {
      return !this.isDir && this.node.name.startsWith('_')
    },
    // 缩进：每层 14px，根层（depth=0）也留 4px 起始
    indentStyle() {
      return { paddingLeft: (4 + this.depth * 14) + 'px' }
    }
  },
  methods: {
    toggle() {
      if (this.isDir) this.expanded = !this.expanded
    },
    onSelect() {
      if (!this.isDir) this.$emit('select', this.node.path)
    }
  },
  render() {
    const self = this
    if (self.isDir) {
      const row = h('div', {
        class: 'tree-node tree-dir',
        style: self.indentStyle,
        onClick: self.toggle
      }, [
        h('span', { class: ['tree-arrow', { expanded: self.expanded }] }, '▸'),
        h('span', { class: 'tree-folder-icon' }, '❒'),
        h('span', { class: 'tree-label tree-folder-label' }, self.node.name)
      ])
      const children = h('ul', {
        class: 'tree-children',
        style: { display: self.expanded ? '' : 'none' }
      }, self.node.children.map(child => h(MemoryTreeNode, {
        key: child.path,
        node: child,
        depth: self.depth + 1,
        currentPath: self.currentPath,
        onSelect: (p) => self.$emit('select', p)
      })))
      return h('li', { class: 'tree-node-wrap' }, [row, children])
    }
    const row = h('div', {
      class: ['tree-node tree-file', { active: self.isActive }],
      style: self.indentStyle,
      title: self.node.path,
      onClick: self.onSelect
    }, [
      h('span', { class: 'tree-arrow placeholder' }, '▸'),
      h('span', { class: 'tree-file-icon' }, '∊'),
      h('span', { class: 'tree-label tree-file-label' }, self.node.name),
      self.isTemplate ? h('span', { class: 'tree-template-badge' }, '模板') : null
    ])
    return h('li', { class: 'tree-node-wrap' }, row)
  }
}

export default {
  name: 'MemoryPanel',
  components: { MemoryTreeNode },
  data() {
    return {
      files: [],
      currentPath: '',
      rawContent: '',       // 磁盘原文（保存后同步）
      editContent: '',      // 编辑缓冲
      mode: 'view',         // 'view' | 'edit'
      loading: false,
      saving: false,
      rebuilding: false
    }
  },
  computed: {
    dirty() {
      return this.editContent !== this.rawContent
    },
    previewHtml() {
      return renderToHtml(this.rawContent)
    },
    /**
     * 将扁平路径数组构建为嵌套树。
     * 文件夹节点 { type:'dir', name, path, children, expanded:false }；
     * 文件节点 { type:'file', name, path }。
     * 文件夹排前，同类按名字字典序。根级文件与根级文件夹并列。
     */
    fileTree() {
      return buildTree(this.files)
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

    // 一键重建核心模板：覆盖 Agent.md / User.md / Directory.md，Current 与 History 不受影响
    async rebuildTemplates() {
      try {
        await ElMessageBox.confirm(
          '将强制覆盖 Agent.md / User.md / Directory.md 三个核心模板文件，Current 学习计划与 History 历史归档不受影响。确定继续？',
          '一键重建核心模板',
          { confirmButtonText: '重建', cancelButtonText: '取消', type: 'warning' }
        )
      } catch {
        return
      }
      this.rebuilding = true
      try {
        const { data } = await memoryApi.rebuildTemplates()
        if (data.code === 1) {
          ElMessage.success(`已重建 ${data.data?.length || 0} 个核心模板`)
          await this.loadFiles()
          // 若当前正查看的文件被重建，刷新其内容
          if (this.currentPath && data.data?.includes(this.currentPath)) {
            await this.selectFile(this.currentPath)
          }
        } else {
          ElMessage.error(data.msg || '重建失败')
        }
      } catch (e) {
        ElMessage.error('重建失败: ' + (e.response?.data?.msg || e.message))
      } finally {
        this.rebuilding = false
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
    }
  }
}

/**
 * 扁平路径数组 → 嵌套树。
 * 例：['Agent.md', 'History/A.md', 'Learning/Current/B.md']
 *   → [{file Agent.md}, {dir History [file A.md]}, {dir Learning [dir Current [file B.md]]}]
 * 文件夹默认 expanded=false；排序：dir 优先、再按 name 字典序。
 */
function buildTree(paths) {
  const root = { children: [] }
  for (const p of paths) {
    const segs = p.split('/')
    let cur = root
    segs.forEach((seg, i) => {
      const isLeaf = i === segs.length - 1
      if (isLeaf) {
        cur.children.push({ type: 'file', name: seg, path: p })
      } else {
        const dirPath = segs.slice(0, i + 1).join('/')
        let next = cur.children.find(n => n.type === 'dir' && n.name === seg)
        if (!next) {
          next = { type: 'dir', name: seg, path: dirPath, children: [] }
          cur.children.push(next)
        }
        cur = next
      }
    })
  }
  // 递归排序：dir 优先，同类按 name
  const sortNode = (n) => {
    if (n.type === 'dir') {
      n.children.sort((a, b) => {
        if (a.type !== b.type) return a.type === 'dir' ? -1 : 1
        return a.name.localeCompare(b.name)
      })
      n.children.forEach(sortNode)
    }
  }
  root.children.sort((a, b) => {
    if (a.type !== b.type) return a.type === 'dir' ? -1 : 1
    return a.name.localeCompare(b.name)
  })
  root.children.forEach(sortNode)
  return root.children
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

.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 侧边栏头部文字按钮：比工具栏更紧凑，适配 260px 侧边栏 */
.sidebar-action-btn {
  padding: 3px 10px;
  font-size: 12px;
}

.file-list {
  list-style: none;
  margin: 0;
  padding: 8px;
  overflow-y: auto;
  flex: 1;
}

/* 树节点行：箭头 + 图标 + 标签。
   :deep() 穿透到内联递归子组件 MemoryTreeNode 的 DOM */
:deep(.tree-node) {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 7px;
  padding-bottom: 7px;
  padding-right: 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
  border-left: 3px solid transparent;
  user-select: none;
}
:deep(.tree-node:hover) {
  background: #faf6ee;
}
:deep(.tree-node.active) {
  background: #faf6ee;
  border-left-color: #b8763d;
}

/* 展开箭头：折叠 ▸、展开旋转 90° 到 ▾ */
:deep(.tree-arrow) {
  width: 12px;
  flex-shrink: 0;
  text-align: center;
  color: #8a948f;
  font-size: 11px;
  line-height: 1;
  transition: transform 0.15s, color 0.15s;
}
:deep(.tree-arrow.expanded) {
  transform: rotate(90deg);
  color: #b8763d;
}
:deep(.tree-arrow.placeholder) {
  visibility: hidden;
}

/* 文件夹/文件小图标 */
:deep(.tree-folder-icon),
:deep(.tree-file-icon) {
  width: 14px;
  flex-shrink: 0;
  text-align: center;
  font-size: 12px;
  line-height: 1;
}
:deep(.tree-folder-icon) {
  color: #b8763d;
  opacity: 0.75;
}
:deep(.tree-file-icon) {
  color: #a89e8a;
}

:deep(.tree-label) {
  font-size: 13.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
:deep(.tree-folder-label) {
  color: #1a2e2a;
  font-weight: 600;
}
:deep(.tree-file-label) {
  color: #2a322e;
  font-weight: 400;
}
:deep(.tree-node.active .tree-file-label) {
  color: #b8763d;
  font-weight: 500;
}

/* 结构样板文件（_ 开头，如 _template.md）徽标：区别于真实记忆文件 */
:deep(.tree-template-badge) {
  margin-left: auto;
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 8px;
  background: #f0ead9;
  color: #a89e8a;
  font-size: 11px;
  line-height: 1.4;
}

/* 子节点容器：去掉默认 ul 缩进 */
:deep(.tree-children) {
  list-style: none;
  margin: 0;
  padding: 0;
}

:deep(.tree-node-wrap) {
  list-style: none;
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
