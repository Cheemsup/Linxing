<template>
  <div class="tree-node-wrapper" :style="{ marginTop: depth === 0 ? '0' : '8px' }">
    <div
      :class="['tree-node-rect', { active: isActive }]"
      :title="node.content"
      @click="handleClick"
    >
      <span v-if="depth > 0" class="node-depth">L{{ depth }}</span>
      <span class="node-text">{{ displayText }}</span>
    </div>
    <div v-if="node.children && node.children.length" class="tree-children-row">
      <div v-for="child in node.children" :key="child.id" class="tree-child-col">
        <QuestionNode
          :node="child"
          :active-path="activePath"
          :depth="depth + 1"
          @select="onSelect"
        />
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'QuestionNode',
  props: {
    node: { type: Object, required: true },
    activePath: { type: Set, default: () => new Set() },
    depth: { type: Number, default: 0 }
  },
  emits: ['select'],
  computed: {
    isActive() {
      return this.activePath.has(this.node.id)
    },
    displayText() {
      const text = this.node.content || ''
      if (text.length > 40) {
        return text.substring(0, 40) + '...'
      }
      return text
    }
  },
  methods: {
    handleClick() {
      this.$emit('select', this.node.id)
    },
    onSelect(id) {
      this.$emit('select', id)
    }
  }
}
</script>

<style scoped>
.tree-node-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
}

.tree-node-rect {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 18px;
  border: 2px solid #d0d5dd;
  border-radius: 10px;
  background: white;
  max-width: 280px;
  min-width: 80px;
  cursor: pointer;
  transition: all 0.2s ease;
  position: relative;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  white-space: nowrap;
}

.tree-node-rect:hover {
  border-color: #b8763d;
  box-shadow: 0 2px 8px rgba(184, 118, 61, 0.2);
  transform: translateY(-1px);
}

.tree-node-rect.active {
  border-color: #b8763d;
  background: linear-gradient(135deg, #f7eede, #ecd9b8);
  box-shadow: 0 2px 8px rgba(184, 118, 61, 0.25);
  font-weight: 600;
}

.node-depth {
  font-size: 10px;
  color: #999;
  background: #f0f0f0;
  padding: 1px 6px;
  border-radius: 4px;
  flex-shrink: 0;
}

.tree-node-rect.active .node-depth {
  background: #b8763d;
  color: white;
}

.node-text {
  font-size: 13px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.4;
}

.tree-children-row {
  display: flex;
  justify-content: center;
  position: relative;
  padding-top: 36px;
}

.tree-children-row::before {
  content: '';
  position: absolute;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  width: 0;
  height: 18px;
  border-left: 2px solid #c4c8d0;
}

.tree-child-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
  padding: 0 12px;
}

.tree-child-col::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: #c4c8d0;
}

.tree-child-col:first-child::before {
  left: 50%;
  right: 0;
}

.tree-child-col:last-child::before {
  left: 0;
  right: 50%;
}

.tree-child-col:only-child::before {
  display: none;
}

.tree-child-col::after {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  width: 0;
  height: 18px;
  border-left: 2px solid #c4c8d0;
}
</style>
