import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      vue: 'vue/dist/vue.esm-bundler.js'
    }
  },
  server: {
    // 固定监听 IPv4 回环地址，避免 localhost 的 IPv4/IPv6 解析差异导致浏览器无法连接。
    host: '127.0.0.1',
    proxy: {
      '/api': 'http://localhost:9527'
    }
  }
})
