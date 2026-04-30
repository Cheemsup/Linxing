<template>
  <div class="tree-node">
    <div
      :class="['node-content', { selected: selectedChunkId === node.chunkId }]"
      @click="handleClick"
    >
      <span
        v-if="hasChildren"
        :class="['expand-icon', { expanded: isExpanded }]"
        @click.stop="toggleExpand"
      >▶</span>
      <span v-else class="expand-placeholder"></span>
      <span :class="['chunk-type-badge', node.chunkType]">{{ getTypeLabel(node.chunkType) }}</span>
      <span class="node-title" :title="node.titlePath || node.textPreview">
        {{ node.titlePath || truncateText(node.textPreview, 40) }}
      </span>
    </div>
    <div v-if="hasChildren && isExpanded" class="node-children">
      <ChunkTreeNode
        v-for="child in node.children"
        :key="child.chunkId"
        :node="child"
        :selected-chunk-id="selectedChunkId"
        @select="$emit('select', $event)"
      />
    </div>
  </div>
</template>

<script>
export default {
  name: 'ChunkTreeNode',
  props: {
    node: {
      type: Object,
      required: true
    },
    selectedChunkId: {
      type: Number,
      default: null
    }
  },
  emits: ['select'],
  data() {
    return {
      isExpanded: true
    }
  },
  computed: {
    hasChildren() {
      return this.node.children && this.node.children.length > 0
    }
  },
  methods: {
    handleClick() {
      this.$emit('select', this.node)
    },
    toggleExpand() {
      this.isExpanded = !this.isExpanded
    },
    getTypeLabel(type) {
      const map = {
        section: '标题',
        general: '段落',
        code: '代码',
        table: '表格',
        qa_pair: '问答',
        context_weak: '弱上下文'
      }
      return map[type] || '其他'
    },
    truncateText(text, maxLen) {
      if (!text) return '(空)'
      return text.length <= maxLen ? text : text.substring(0, maxLen) + '...'
    }
  }
}
</script>

<style scoped>
.tree-node {
  font-size: 13px;
}

.node-content {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  cursor: pointer;
  border-radius: 4px;
  margin: 1px 8px;
  transition: background 0.15s;
}

.node-content:hover {
  background: #e3f2fd;
}

.node-content.selected {
  background: #bbdefb;
}

.expand-icon {
  font-size: 10px;
  color: #666;
  width: 14px;
  text-align: center;
  transition: transform 0.2s;
  flex-shrink: 0;
}

.expand-icon.expanded {
  transform: rotate(90deg);
}

.expand-placeholder {
  width: 14px;
  flex-shrink: 0;
}

.chunk-type-badge {
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 500;
  flex-shrink: 0;
}

.chunk-type-badge.section {
  background: #e3f2fd;
  color: #1565c0;
}

.chunk-type-badge.general {
  background: #f5f5f5;
  color: #666;
}

.chunk-type-badge.code {
  background: #f3e5f5;
  color: #7b1fa2;
}

.chunk-type-badge.table {
  background: #e8f5e9;
  color: #2e7d32;
}

.chunk-type-badge.qa_pair {
  background: #fff3e0;
  color: #e65100;
}

.chunk-type-badge.context_weak {
  background: #fce4ec;
  color: #c62828;
}

.node-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #333;
}

.node-children {
  padding-left: 16px;
}
</style>
