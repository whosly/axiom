import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        // 按依赖来源拆包：避免单个 1.6MB bundle，并提升浏览器缓存命中率
        // Split by dependency group to avoid a single 1.6MB bundle and improve cache hits.
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('echarts') || id.includes('zrender')) return 'charts';
            if (id.includes('tdesign')) return 'tdesign';
            // React 生态与其余依赖统一归入 vendor，避免跨 chunk 循环引用
            // Keep the React ecosystem with other deps to avoid circular chunks.
            return 'vendor';
          }
        },
      },
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  css: {
    preprocessorOptions: {
      less: {
        javascriptEnabled: true
      }
    }
  }
});
