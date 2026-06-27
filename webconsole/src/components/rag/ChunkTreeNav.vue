<template>
  <div class="tree-nav-overlay" @click.self="$emit('close')" @keydown.escape="$emit('close')">
    <div class="tree-nav-container">
      <div class="tree-header">
        <h3>{{ fileName }}</h3>
        <button @click="$emit('close')" class="btn-close" title="关闭">
          <el-icon><Close /></el-icon>
        </button>
      </div>

      <div v-if="loading" class="tree-loading">
        <span class="spinner"></span> 加载中...
      </div>

      <div v-else-if="treeData.length === 0" class="tree-empty">
        该文档暂无分块信息
      </div>

      <div v-else class="tree-content">
        <div class="tree-filter">
          <button
            v-for="filter in typeFilters"
            :key="filter.value"
            :class="['filter-chip', { active: activeTypeFilter === filter.value }]"
            @click="setFilter(filter.value)"
          >{{ filter.label }}</button>
        </div>
        <div v-for="node in treeData" :key="node.chunkId" class="tree-node-wrapper">
          <ChunkTreeNode
            :node="node"
            :selected-chunk-id="selectedChunkId"
            :active-type-filter="activeTypeFilter"
            :document-id="documentId"
            @select="handleSelect"
          />
        </div>
      </div>

      <div class="tree-footer">
        共 {{ nodeCount }} 个节点
      </div>
    </div>
  </div>
</template>

<script>
import { documentApi } from '@/api/rag/document'
import ChunkTreeNode from './ChunkTreeNode.vue'

export default {
  name: 'ChunkTreeNav',
  components: {
    ChunkTreeNode
  },
  props: {
    documentId: {
      type: Number,
      required: true
    },
    fileName: {
      type: String,
      default: ''
    }
  },
  emits: ['close', 'select'],
  data() {
    return {
      treeData: [],
      loading: false,
      selectedChunkId: null,
      activeTypeFilter: null,
      typeFilters: [
        { label: '全部', value: null },
        { label: '标题', value: 'section' },
        { label: '段落', value: 'general' },
        { label: '代码', value: 'code' },
        { label: '表格', value: 'table' },
        { label: '问答', value: 'qa_pair' },
        { label: '弱上下文', value: 'context_weak' }
      ]
    }
  },
  computed: {
    nodeCount() {
      const countRecursive = (nodes) => {
        return nodes.reduce((sum, node) => {
          return sum + 1 + (node.children ? countRecursive(node.children) : 0)
        }, 0)
      }
      return countRecursive(this.treeData)
    }
  },
  mounted() {
    this.loadTree()
    document.addEventListener('keydown', this.handleEscape)
  },
  beforeUnmount() {
    document.removeEventListener('keydown', this.handleEscape)
  },
  methods: {
    handleEscape(e) {
      if (e.key === 'Escape') {
        this.$emit('close')
      }
    },
    async loadTree() {
      this.loading = true
      try {
        const response = await documentApi.getChunkTree(this.documentId)
        const resData = response.data
        if (resData.code === 1) {
          this.treeData = resData.data || []
        }
      } catch (error) {
        console.error('加载chunk树失败:', error)
      } finally {
        this.loading = false
      }
    },
    handleSelect(node) {
      this.selectedChunkId = node.chunkId
      this.$emit('select', node)
    },
    setFilter(value) {
      this.activeTypeFilter = value
    }
  }
}
</script>

<style scoped>
.tree-nav-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1001;
}

.tree-nav-container {
  width: 90%;
  max-width: 700px;
  max-height: 80vh;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.18);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.tree-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #eee;
  flex-shrink: 0;
}

.tree-header h3 {
  margin: 0;
  font-size: 15px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.btn-close {
  background: none;
  border: none;
  font-size: 18px;
  color: #999;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  line-height: 1;
}

.btn-close:hover {
  background: #f0f0f0;
  color: #333;
}

.tree-loading,
.tree-empty {
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

.tree-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.tree-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 16px;
  border-bottom: 1px solid #f0f0f0;
}

.filter-chip {
  padding: 3px 10px;
  border: 1px solid #ddd;
  border-radius: 12px;
  background: #fff;
  color: #666;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.filter-chip:hover {
  border-color: #b8763d;
  color: #b8763d;
}

.filter-chip.active {
  background: #b8763d;
  border-color: #b8763d;
  color: #fff;
}

.tree-footer {
  padding: 10px 20px;
  border-top: 1px solid #eee;
  text-align: right;
  font-size: 12px;
  color: #999;
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .tree-nav-container {
    width: 95vw;
    max-height: 90vh;
    border-radius: 8px;
  }

  .tree-header {
    padding: 12px 16px;
  }

  .tree-header h3 {
    font-size: 14px;
  }
}
</style>
