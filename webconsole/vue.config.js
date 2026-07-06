const { defineConfig } = require('@vue/cli-service')
module.exports = defineConfig({
  transpileDependencies: true,
  devServer: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        pathRewrite: {
          '^/api': ''
        }
      },
      // chunk 图片静态资源（后端 WebMvcConfig 暴露的 /chunk_images/**，不带 /api 前缀）
      '/chunk_images': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
