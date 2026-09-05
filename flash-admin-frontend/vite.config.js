import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      '/admin': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 商品本地图：/images/{filename} 由网关路由到 flash-api，从磁盘 images/ 目录读取
      '/images': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
