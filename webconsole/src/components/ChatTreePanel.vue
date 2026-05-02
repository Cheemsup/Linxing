<template>
  <div class="chat-tree-panel">
    <div class="tree-header">
      <h3>对话树</h3>
      <span class="tree-hint">点击选中节点，再点「确认跳转」切换对话</span>
      <button class="btn-close" @click="$emit('close')" title="关闭">✕</button>
    </div>
    <div class="tree-body">
      <div v-if="treeData === null" class="tree-empty">
        暂无对话记录
      </div>
      <div v-else class="tree-chart-container">
        <VueTree
          ref="treeRef"
          :data="treeData"
          direction="horizontal"
          :childrenKey="'children'"
          :wheelZoom="true"
          :hierarchyMargin="80"
          :neighborMargin="16"
          :showKnot="true"
          :stretchLength="16"
        >
          <template #node="{ data }">
            <div
              class="tree-custom-node"
              :class="nodeClass(data.nodeId)"
              :title="data.fullContent"
              @click.stop="handleNodeClick(data)"
            >
              {{ data.label }}
            </div>
          </template>
        </VueTree>
      </div>
    </div>
    <div class="tree-toolbar">
      <button class="btn-reset" @click="resetZoom" title="重置缩放和拖拽位置">重置视图</button>
      <button
        class="btn-confirm"
        :disabled="!selectedNodeId"
        @click="confirmSelection"
      >
        确认跳转
      </button>
      <span class="current-node-label">
        当前选中：{{ selectedNodeLabel || '未选择' }}
      </span>
    </div>
  </div>
</template>

<script>
import VueTree from 'vue3-d3-tree'

export default {
  name: 'ChatTreePanel',
  components: {
    VueTree
  },
  props: {
    roots: {
      type: Array,
      default: () => []
    },
    activePath: {
      type: Set,
      default: () => new Set()
    }
  },
  emits: ['close', 'select'],
  data() {
    return {
      selectedNodeId: null,
      selectedNodeLabel: ''
    }
  },
  computed: {
    treeData() {
      if (!this.roots || this.roots.length === 0) return null

      const mapNode = (node) => {
        const mapped = {
          label: this.truncateLabel(node.content || ''),
          nodeId: node.id,
          fullContent: node.content || ''
        }
        if (node.children && node.children.length > 0) {
          mapped.children = node.children.map(mapNode)
        }
        return mapped
      }

      const mapped = this.roots.map(mapNode)
      if (mapped.length === 1) return mapped[0]

      return {
        label: '全部对话',
        nodeId: null,
        children: mapped
      }
    }
  },
  methods: {
    truncateLabel(text) {
      if (text.length > 24) {
        return text.substring(0, 24) + '...'
      }
      return text
    },
    nodeClass(nodeId) {
      if (this.selectedNodeId === nodeId) {
        return 'tree-node-selected'
      }
      if (this.activePath.has(nodeId)) {
        return 'tree-node-active'
      }
      return ''
    },
    handleNodeClick(data) {
      if (!data.nodeId) return
      this.selectedNodeId = data.nodeId
      this.selectedNodeLabel = data.label
    },
    confirmSelection() {
      if (!this.selectedNodeId) return
      this.$emit('select', this.selectedNodeId)
    },
    resetZoom() {
      this.$refs.treeRef?.zoom(1)
    }
  }
}
</script>

<style scoped>
.chat-tree-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fafbfc;
}

.tree-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid #e8e8e8;
  background: white;
  flex-shrink: 0;
}

.tree-header h3 {
  margin: 0;
  font-size: 16px;
  color: #333;
  white-space: nowrap;
}

.tree-hint {
  flex: 1;
  font-size: 12px;
  color: #999;
}

.btn-close {
  background: none;
  border: none;
  font-size: 18px;
  color: #999;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.15s;
}

.btn-close:hover {
  background: #f0f0f0;
  color: #333;
}

.tree-body {
  flex: 1;
  overflow: hidden;
  position: relative;
}

.tree-empty {
  text-align: center;
  color: #999;
  padding: 60px 16px;
  font-size: 14px;
}

.tree-chart-container {
  width: 100%;
  height: 100%;
}

.tree-custom-node {
  padding: 8px 16px;
  border: 2px solid #d0d5dd;
  border-radius: 8px;
  background: white;
  font-size: 13px;
  color: #333;
  cursor: pointer;
  max-width: 220px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: all 0.15s;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  user-select: none;
}

.tree-custom-node:hover {
  border-color: #667eea;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.2);
}

.tree-node-active {
  border-color: #1a73e8;
  background: linear-gradient(135deg, #e8f0fe, #d2e3fc);
  box-shadow: 0 2px 8px rgba(26, 115, 232, 0.25);
  font-weight: 600;
}

.tree-node-selected {
  border-color: #e65100;
  background: linear-gradient(135deg, #fff3e0, #ffe0b2);
  box-shadow: 0 2px 8px rgba(230, 81, 0, 0.3);
  font-weight: 600;
}

.tree-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  border-top: 1px solid #e8e8e8;
  background: white;
  flex-shrink: 0;
}

.btn-reset {
  padding: 6px 14px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.2s;
}

.btn-reset:hover {
  background: #5a6fd6;
}

.btn-confirm {
  padding: 6px 14px;
  background: #e65100;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.2s;
}

.btn-confirm:hover:not(:disabled) {
  background: #bf360c;
}

.btn-confirm:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.current-node-label {
  flex: 1;
  font-size: 13px;
  color: #666;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
