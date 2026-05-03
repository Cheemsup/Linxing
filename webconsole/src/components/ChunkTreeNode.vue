<template>
  <div
    :class="['tree-node', { dimmed: activeTypeFilter && node.chunkType !== activeTypeFilter }]"
  >
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
      <span class="sibling-index">{{ node.siblingIndex }}</span>
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
        :active-type-filter="activeTypeFilter"
        :document-id="documentId"
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
    },
    activeTypeFilter: {
      type: String,
      default: null
    },
    documentId: {
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
    },
    storageKey() {
      return `chunk-tree-state:${this.documentId}`
    }
  },
  mounted() {
    this.restoreState()
  },
  methods: {
    getCollapsedSet() {
      try {
        const raw = localStorage.getItem(this.storageKey)
        return raw ? new Set(JSON.parse(raw)) : new Set()
      } catch {
        return new Set()
      }
    },
    saveCollapsedSet(set) {
      try {
        localStorage.setItem(this.storageKey, JSON.stringify([...set]))
      } catch {
        // ignore quota errors
      }
    },
    restoreState() {
      if (this.hasChildren) {
        const collapsedSet = this.getCollapsedSet()
        this.isExpanded = !collapsedSet.has(this.node.chunkId)
      }
    },
    handleClick() {
      this.$emit('select', this.node)
    },
    toggleExpand() {
      this.isExpanded = !this.isExpanded
      if (this.documentId != null) {
        const collapsedSet = this.getCollapsedSet()
        if (this.isExpanded) {
          collapsedSet.delete(this.node.chunkId)
        } else {
          collapsedSet.add(this.node.chunkId)
        }
        this.saveCollapsedSet(collapsedSet)
      }
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

.tree-node.dimmed > .node-content {
  opacity: 0.3;
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

.sibling-index {
  color: #bbb;
  font-size: 11px;
  font-weight: 500;
  min-width: 18px;
  text-align: right;
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
</style>
