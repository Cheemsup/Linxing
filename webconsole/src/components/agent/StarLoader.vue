<template>
  <span class="star-loader" :style="{ '--star-size': size + 'px' }">
    <svg
      class="star-loader-svg"
      :width="size"
      :height="size"
      viewBox="0 0 48 48"
      aria-hidden="true"
    >
      <!-- 四芒星 path，复用 ChatHomePanel/LoginView 的品牌 logo -->
      <path
        d="M24 3 L27.5 20.5 L45 24 L27.5 27.5 L24 45 L20.5 27.5 L3 24 L20.5 20.5 Z"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linejoin="round"
      />
    </svg>
    <span v-if="showElapsed && elapsedSeconds != null" class="star-loader-elapsed">
      已 {{ elapsedSeconds }} 秒
    </span>
  </span>
</template>

<script>
/**
 * 四芒星旋转加载动画（品牌化等待指示器）。
 * 复用首页/登录页的四芒星 SVG path + 棕橙品牌色 var(--accent)，取代原 step-spin 小图标旋转。
 *
 * 作用：
 *  - 工具执行中（tool_progress 心跳驱动）显示"已 N 秒"计时 + 持续旋转，明确感知"还在跑"
 *  - 思考/等待回答等通用加载场景
 */
export default {
  name: 'StarLoader',
  props: {
    /** SVG 尺寸（px） */
    size: {
      type: Number,
      default: 16
    },
    /** 累计执行秒数，来自 tool_progress 心跳 stepData.elapsed_seconds；null 时不显示计时 */
    elapsedSeconds: {
      type: Number,
      default: null
    },
    /** 是否显示"已 N 秒"计时文案 */
    showElapsed: {
      type: Boolean,
      default: true
    }
  }
}
</script>

<style scoped>
.star-loader {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--accent, #b8763d);
}

.star-loader-svg {
  /* 2.5s 一圈，比 step-spin(1s) 更优雅从容 */
  animation: star-rotate 2.5s linear infinite;
  transform-origin: center;
}

@keyframes star-rotate {
  from {
    transform: rotate(0deg);
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
  to {
    transform: rotate(360deg);
    opacity: 0.45;
  }
}

.star-loader-elapsed {
  font-size: 12px;
  font-style: italic;
  color: var(--accent, #b8763d);
  white-space: nowrap;
}
</style>
