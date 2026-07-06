<template>
  <div class="rich-chunk-text">
    <template v-for="(seg, i) in segments" :key="i">
      <span v-if="seg.kind === 'text'" class="rct-text">{{ seg.text }}</span>

      <span v-else-if="seg.kind === 'image'" class="rct-image-wrap">
        <img
          v-if="seg.src"
          :src="seg.src"
          :alt="seg.caption || ('图片 ' + seg.nodeId)"
          class="rct-image"
          loading="lazy"
          @error="onImgError(seg, $event)"
        />
        <span v-else class="rct-fallback" :title="`未找到图片元信息: ${seg.nodeId}`">[[LINXING:IMAGE:{{ seg.nodeId }}]]</span>
        <span v-if="seg.caption" class="rct-caption">{{ seg.caption }}</span>
      </span>

      <span v-else-if="seg.kind === 'code'" class="rct-code-wrap">
        <code v-if="seg.code" class="rct-code" :data-lang="seg.language || ''">{{ seg.code }}</code>
        <span v-else class="rct-fallback">[[LINXING:CODE:{{ seg.nodeId }}]]</span>
      </span>

      <span v-else-if="seg.kind === 'table'" class="rct-table-wrap">
        <span v-if="seg.html" class="rct-table" v-html="seg.html"></span>
        <span v-else class="rct-fallback">[[LINXING:TABLE:{{ seg.nodeId }}]]</span>
      </span>

      <span v-else-if="seg.kind === 'formula'" class="rct-formula-wrap">
        <code v-if="seg.formula" class="rct-formula">{{ seg.formula }}</code>
        <span v-else class="rct-fallback">[[LINXING:FORMULA:{{ seg.nodeId }}]]</span>
      </span>
    </template>
  </div>
</template>

<script>
const PLACEHOLDER_RE = /\[\[LINXING:([A-Z]+):([A-Za-z0-9_]+)\]\]/g

export default {
  name: 'RichChunkText',
  props: {
    chunkText: { type: String, default: '' },
    nodeMetadata: { type: Array, default: () => [] }
  },
  computed: {
    metaMap() {
      const m = new Map()
      for (const item of this.nodeMetadata || []) {
        if (item && item.id) m.set(String(item.id), item)
      }
      return m
    },
    segments() {
      if (!this.chunkText) return []
      const segs = []
      let last = 0
      let m
      PLACEHOLDER_RE.lastIndex = 0
      while ((m = PLACEHOLDER_RE.exec(this.chunkText)) !== null) {
        if (m.index > last) {
          segs.push({ kind: 'text', text: this.chunkText.slice(last, m.index) })
        }
        const type = m[1].toLowerCase()
        const nodeId = m[2]
        const meta = this.metaMap.get(nodeId) || {}
        if (type === 'image') {
          segs.push({ kind: 'image', nodeId, src: meta.imagePath || '', caption: meta.caption || '' })
        } else if (type === 'code') {
          segs.push({ kind: 'code', nodeId, code: meta.code || '', language: meta.language || '' })
        } else if (type === 'table') {
          segs.push({ kind: 'table', nodeId, html: meta.html || '' })
        } else if (type === 'formula') {
          segs.push({ kind: 'formula', nodeId, formula: meta.formula || '' })
        } else {
          segs.push({ kind: 'text', text: m[0] })
        }
        last = m.index + m[0].length
      }
      if (last < this.chunkText.length) {
        segs.push({ kind: 'text', text: this.chunkText.slice(last) })
      }
      return segs
    }
  },
  methods: {
    onImgError(seg, ev) {
      // 加载失败：降级为占位符文本，不报错
      const parent = ev.target.parentNode
      if (!parent) return
      const span = document.createElement('span')
      span.className = 'rct-fallback'
      span.textContent = `[[LINXING:IMAGE:${seg.nodeId}]]`
      parent.replaceChild(span, ev.target)
    }
  }
}
</script>

<style scoped>
.rich-chunk-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.rct-text {
  white-space: pre-wrap;
}

.rct-image-wrap {
  display: block;
  margin: 8px 0;
  text-align: center;
}

.rct-image {
  max-width: 100%;
  border-radius: 6px;
  border: 1px solid #e0e0e0;
  display: inline-block;
}

.rct-caption {
  display: block;
  font-size: 12px;
  color: #888;
  margin-top: 4px;
}

.rct-code-wrap {
  display: block;
  margin: 6px 0;
}

.rct-code {
  display: block;
  background: #f6f8fa;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  padding: 10px 12px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12.5px;
  line-height: 1.5;
  white-space: pre;
  overflow-x: auto;
}

.rct-table-wrap {
  display: block;
  margin: 6px 0;
  overflow-x: auto;
}

.rct-table :deep(table) {
  border-collapse: collapse;
  width: 100%;
  font-size: 13px;
}

.rct-table :deep(th),
.rct-table :deep(td) {
  border: 1px solid #d0d0d0;
  padding: 6px 10px;
  text-align: left;
}

.rct-table :deep(th) {
  background: #f3f4f6;
  font-weight: 600;
}

.rct-formula-wrap {
  display: block;
  margin: 6px 0;
}

.rct-formula {
  display: inline-block;
  background: #faf5ed;
  border: 1px solid #e8d5b8;
  border-radius: 4px;
  padding: 2px 8px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  color: #8a5a1f;
}

.rct-fallback {
  color: #999;
  font-style: italic;
  font-size: 0.95em;
}
</style>
