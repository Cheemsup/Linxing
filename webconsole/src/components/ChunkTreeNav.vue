<template>
  <div class="chunk-tree-nav">
    <div class="tree-header">
      <h3>{{ fileName }}</h3>
      <button @click="$emit('close')" class="btn-close" title="关闭">✕</button>
    </div>

    <div v-if="loading" class="tree-loading">
      <span class="spinner"></span> 加载中...
    </div>

    <div v-else-if="treeData.length === 0" class="tree-empty">
      该文档暂无分块信息
    </div>

    <div v-else class="tree-content">
      <div v-for="node in treeData" :key="node.chunkId" class="tree-node-wrapper">
        <ChunkTreeNode
          :node="node"
          :selected-chunk-id="selectedChunkId"
          @select="handleSelect"
        />
      </div>
    </div>
  </div>
</template>

<script>
import { documentApi } from '@/api'
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
      selectedChunkId: null
    }
  },
  mounted() {
    this.loadTree()
  },
  methods: {
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
    }
  }
}
</script>

<style scoped>
.chunk-tree-nav {
  width: 320px;
  min-width: 320px;
  border-left: 1px solid #e0e0e0;
  display: flex;
  flex-direction: column;
  background: #fafafa;
  height: 100%;
}

.tree-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #eee;
}

.tree-header h3 {
  margin: 0;
  font-size: 14px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
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
  border-top-color: #1a73e8;
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
</style>
