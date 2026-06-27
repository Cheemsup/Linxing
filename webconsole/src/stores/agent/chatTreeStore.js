import { reactive } from 'vue'

const state = reactive({
  messages: [],
  activeLeafId: null,
  branchParentId: null
})

export const chatTreeStore = {
  state,

  setMessages(messages) {
    state.messages = messages
  },

  setActiveLeaf(id) {
    state.activeLeafId = id
  },

  setBranchParent(id) {
    state.branchParentId = id
  },

  clearBranch() {
    state.branchParentId = null
  },

  clearMessages() {
    state.messages = []
    state.activeLeafId = null
    state.branchParentId = null
  },

  getMessageMap() {
    const map = new Map()
    state.messages.forEach(m => map.set(m.id, { ...m, children: [] }))
    state.messages.forEach(m => {
      if (m.parentId && map.has(m.parentId)) {
        map.get(m.parentId).children.push(map.get(m.id))
      }
    })
    return map
  },

  isDescendantOf(ancestorId, nodeId) {
    const map = this.getMessageMap()
    let current = nodeId
    while (current && map.has(current)) {
      if (current === ancestorId) return true
      current = map.get(current).parentId
    }
    return false
  },

  findNewActiveLeaf(excludedId) {
    const map = this.getMessageMap()
    const allIds = Array.from(map.keys()).filter(
      id => id !== excludedId && !this.isDescendantOf(excludedId, id)
    )
    if (allIds.length === 0) return null
    return allIds[allIds.length - 1]
  },

  findLeafDescendant(nodeId) {
    const map = this.getMessageMap()
    let leafId = nodeId
    let current = map.get(nodeId)
    while (current && current.children && current.children.length > 0) {
      current = current.children[current.children.length - 1]
      leafId = current.id
    }
    return leafId
  },

  getActivePathIds() {
    const path = new Set()
    let current = state.activeLeafId
    const map = this.getMessageMap()
    while (current && map.has(current)) {
      path.add(current)
      current = map.get(current).parentId
    }
    return path
  },

  getUserActivePath() {
    const path = new Set()
    let current = state.activeLeafId
    const map = this.getMessageMap()
    while (current && map.has(current)) {
      if (map.get(current).role === 'user') {
        path.add(current)
      }
      current = map.get(current).parentId
    }
    return path
  }
}

export default chatTreeStore
