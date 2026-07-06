<template>
  <div class="search-view">
    <div class="search-header">
      <div class="search-input-wrapper">
        <el-icon class="search-icon-leading"><Search /></el-icon>
        <input
          ref="searchInput"
          v-model="query"
          type="text"
          class="search-input"
          placeholder="输入关键词，搜索你的笔记..."
          @keydown.enter="doSearch"
        />
        <button class="search-btn" @click="doSearch" :disabled="loading || !query.trim()">
          <el-icon v-if="loading" class="is-loading"><Loading /></el-icon>
          <span>{{ loading ? '搜索中' : '搜索' }}</span>
        </button>
      </div>

      <button class="advanced-toggle" @click="advancedOpen = !advancedOpen">
        <el-icon class="toggle-icon"><component :is="advancedOpen ? 'ArrowUp' : 'ArrowDown'" /></el-icon>
        <span>高级选项</span>
        <span v-if="hasAdvancedActive" class="advanced-dot"></span>
      </button>
      <div v-if="advancedOpen" class="advanced-panel">
        <label class="adv-field">
          <span class="adv-label">结果数量</span>
          <select v-model.number="topK" class="topk-select">
            <option :value="0">默认</option>
            <option :value="5">5 条</option>
            <option :value="10">10 条</option>
            <option :value="20">20 条</option>
          </select>
        </label>
        <label class="adv-field adv-switch">
          <span class="adv-label">混合检索</span>
          <input type="checkbox" v-model="hybrid" />
          <span class="switch-hint">{{ hybrid ? '已开启' : '未开启' }}</span>
        </label>
      </div>
    </div>

    <div class="search-results">
      <div v-if="searched && results.length === 0" class="no-results">
        <el-icon class="no-results-icon"><Search /></el-icon>
        <p>未找到与 "<strong>{{ lastQuery }}</strong>" 相关的内容</p>
      </div>

      <div v-if="results.length > 0" class="results-info">
        共找到 <strong>{{ results.length }}</strong> 条结果
      </div>

      <div
        v-for="(item, index) in results"
        :key="item.chunkId"
        class="result-card"
      >
        <div class="result-header">
          <span class="result-index">{{ index + 1 }}</span>
          <div class="relevance">
            <span class="relevance-label">相关度</span>
            <div class="relevance-bar">
              <div class="relevance-fill" :style="{ width: toPercent(item.score) + '%' }"></div>
            </div>
            <span class="relevance-pct">{{ toPercent(item.score) }}%</span>
          </div>
          <span class="result-source">{{ item.fileName }}</span>
          <span v-if="item.titlePath" class="result-titlepath">{{ item.titlePath }}</span>
        </div>
        <div class="result-body">
          <RichChunkText :chunk-text="item.chunkText" :node-metadata="item.nodeMetadata" />
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { searchApi } from '@/api/agent/search'
import RichChunkText from '@/components/agent/RichChunkText.vue'

export default {
  name: 'SearchView',
  components: { RichChunkText },
  data() {
    return {
      query: '',
      topK: 0,
      hybrid: false,
      loading: false,
      searched: false,
      lastQuery: '',
      results: [],
      advancedOpen: false
    }
  },
  computed: {
    hasAdvancedActive() {
      return this.topK !== 0 || this.hybrid
    }
  },
  mounted() {
    this.$nextTick(() => {
      this.$refs.searchInput?.focus()
    })
  },
  methods: {
    toPercent(score) {
      if (typeof score !== 'number' || isNaN(score)) return 0
      const pct = Math.round(score * 100)
      return Math.max(0, Math.min(100, pct))
    },
    async doSearch() {
      const q = this.query.trim()
      if (!q || this.loading) return

      this.loading = true
      this.searched = false
      this.lastQuery = q
      this.results = []

      try {
        const res = await searchApi.search({ query: q, topK: this.topK || null, hybrid: this.hybrid })
        const data = res.data
        if (data.code === 1 && Array.isArray(data.data)) {
          this.results = data.data.sort((a, b) => b.score - a.score)
        } else {
          this.results = []
        }
      } catch (e) {
        console.error('搜索失败:', e)
        const msg = e.response?.data?.msg || e.message
        alert('搜索失败: ' + msg)
      } finally {
        this.loading = false
        this.searched = true
      }
    }
  }
}
</script>

<style scoped>
.search-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fafbfc;
}

.search-header {
  flex-shrink: 0;
  padding: 24px 32px 16px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
}

.search-input-wrapper {
  display: flex;
  align-items: center;
  gap: 12px;
  position: relative;
}

.search-icon-leading {
  position: absolute;
  left: 14px;
  color: #9aa0a6;
  font-size: 18px;
  pointer-events: none;
}

.search-input {
  flex: 1;
  padding: 12px 16px 12px 44px;
  font-size: 16px;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  outline: none;
  transition: border-color 0.2s;
}

.search-input:focus {
  border-color: #b8763d;
}

.search-btn {
  padding: 12px 28px;
  background: #b8763d;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  cursor: pointer;
  transition: background 0.2s;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.search-btn:hover:not(:disabled) {
  background: #a0682f;
}

.search-btn:disabled {
  background: #b0c4de;
  cursor: not-allowed;
}

.is-loading {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.advanced-toggle {
  margin-top: 10px;
  background: none;
  border: none;
  color: #888;
  font-size: 13px;
  cursor: pointer;
  padding: 4px 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.advanced-toggle:hover {
  color: #b8763d;
}

.toggle-icon {
  font-size: 12px;
}

.advanced-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b8763d;
  margin-left: 2px;
}

.advanced-panel {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 12px 16px;
  background: #f7f8fa;
  border-radius: 8px;
}

.adv-field {
  font-size: 13px;
  color: #666;
  display: flex;
  align-items: center;
  gap: 8px;
}

.adv-label {
  color: #555;
  font-weight: 500;
}

.adv-switch {
  cursor: pointer;
}

.switch-hint {
  font-size: 12px;
  color: #999;
}

.topk-select {
  padding: 4px 8px;
  border: 1px solid #d0d0d0;
  border-radius: 4px;
  font-size: 13px;
  outline: none;
}

.search-results {
  flex: 1;
  overflow-y: auto;
  padding: 20px 32px;
}

.no-results {
  text-align: center;
  color: #999;
  font-size: 16px;
  padding: 60px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.no-results-icon {
  font-size: 40px;
  color: #d0d4d9;
}

.results-info {
  font-size: 14px;
  color: #666;
  margin-bottom: 16px;
}

.result-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 16px 20px;
  margin-bottom: 14px;
  transition: box-shadow 0.2s;
}

.result-card:hover {
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}

.result-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.result-index {
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: #b8763d;
  min-width: 24px;
  height: 24px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 6px;
}

.relevance {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.relevance-label {
  font-size: 12px;
  color: #888;
}

.relevance-bar {
  width: 70px;
  height: 6px;
  background: #eef0f3;
  border-radius: 3px;
  overflow: hidden;
}

.relevance-fill {
  height: 100%;
  background: linear-gradient(90deg, #4caf50, #b8763d);
  border-radius: 3px;
  transition: width 0.3s;
}

.relevance-pct {
  font-size: 12px;
  font-weight: 600;
  color: #b8763d;
  min-width: 32px;
}

.result-source {
  font-size: 13px;
  font-weight: 500;
  color: #333;
}

.result-titlepath {
  font-size: 12px;
  color: #888;
}

.result-titlepath::before {
  content: '> ';
}

.result-body {
  margin-bottom: 6px;
}

.result-text {
  font-size: 14px;
  line-height: 1.7;
  color: #444;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
