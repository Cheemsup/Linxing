<template>
  <div class="chat-tree-panel">
    <div class="tree-header">
      <h3>对话树</h3>
      <span class="tree-hint">仅显示用户提问，高亮为当前活跃路径</span>
      <button class="btn-close" @click="$emit('close')" title="关闭">✕</button>
    </div>
    <div class="tree-body" ref="treeBody">
      <div v-if="roots.length === 0" class="tree-empty">
        暂无对话记录
      </div>
      <div v-else class="tree-container">
        <div v-for="root in roots" :key="root.id" class="tree-root-branch">
          <QuestionNode
            :node="root"
            :active-path="activePath"
            :depth="0"
            @select="handleSelect"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import QuestionNode from './QuestionNode.vue'

export default {
  name: 'ChatTreePanel',
  components: {
    QuestionNode
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
  methods: {
    handleSelect(id) {
      this.$emit('select', id)
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
  overflow: auto;
  padding: 24px 16px;
}

.tree-empty {
  text-align: center;
  color: #999;
  padding: 60px 16px;
  font-size: 14px;
}

.tree-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: max-content;
  padding-bottom: 40px;
}

.tree-root-branch {
  display: flex;
  flex-direction: column;
  align-items: center;
}
</style>
