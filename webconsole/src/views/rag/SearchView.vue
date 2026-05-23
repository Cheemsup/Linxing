<template>
  <div class="search-view">
    <div class="search-header">
      <div class="search-input-wrapper">
        <input
          ref="searchInput"
          v-model="query"
          type="text"
          class="search-input"
          placeholder="输入关键词搜索你的知识库..."
          @keydown.enter="doSearch"
        />
        <button class="search-btn" @click="doSearch" :disabled="loading || !query.trim()">
          {{ loading ? '搜索中...' : '搜索' }}
        </button>
      </div>
      <div class="search-options">
        <label class="topk-label">
          返回条数:
          <select v-model.number="topK" class="topk-select">
            <option :value="0">默认</option>
            <option :value="5">5</option>
            <option :value="10">10</option>
            <option :value="20">20</option>
          </select>
        </label>
        <label class="hybrid-label">
          <input type="checkbox" v-model="hybrid" />
          BM25 混合检索
        </label>
      </div>
    </div>

    <div class="search-results">
      <div v-if="searched && results.length === 0" class="no-results">
        未找到与 "<strong>{{ lastQuery }}</strong>" 相关的内容
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
          <span class="result-index">#{{ index + 1 }}</span>
          <span class="result-score" :title="'相关性分数: ' + item.score">
            score: {{ item.score.toFixed(4) }}
          </span>
          <span class="result-source">{{ item.fileName }}</span>
          <span v-if="item.titlePath" class="result-titlepath">{{ item.titlePath }}</span>
          <span v-if="item.chunkType" class="result-chunktype">{{ item.chunkType }}</span>
        </div>
        <div class="result-body">
          <p class="result-text">{{ item.chunkText }}</p>
        </div>
        <div v-if="item.documentId" class="result-footer">
          <span class="result-docid">文档ID: {{ item.documentId }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { searchApi } from '@/api/rag/search'

export default {
  name: 'SearchView',
  data() {
    return {
      query: '',
      topK: 0,
      hybrid: false,
      loading: false,
      searched: false,
      lastQuery: '',
      results: []
    }
  },
  mounted() {
    this.$nextTick(() => {
      this.$refs.searchInput?.focus()
    })
  },
  methods: {
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
  gap: 12px;
}

.search-input {
  flex: 1;
  padding: 12px 16px;
  font-size: 16px;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  outline: none;
  transition: border-color 0.2s;
}

.search-input:focus {
  border-color: #1a73e8;
}

.search-btn {
  padding: 12px 28px;
  background: #1a73e8;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  cursor: pointer;
  transition: background 0.2s;
  white-space: nowrap;
}

.search-btn:hover:not(:disabled) {
  background: #1557b0;
}

.search-btn:disabled {
  background: #b0c4de;
  cursor: not-allowed;
}

.search-options {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 20px;
}

.topk-label {
  font-size: 13px;
  color: #888;
  display: flex;
  align-items: center;
  gap: 6px;
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
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.result-index {
  font-size: 12px;
  color: #bbb;
  min-width: 24px;
}

.result-score {
  font-size: 13px;
  font-weight: 600;
  color: #1a73e8;
  background: #e8f0fe;
  padding: 2px 8px;
  border-radius: 4px;
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

.result-chunktype {
  font-size: 11px;
  color: #aaa;
  background: #f5f5f5;
  padding: 2px 6px;
  border-radius: 3px;
  margin-left: auto;
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

.result-footer {
  font-size: 11px;
  color: #bbb;
}
</style>
