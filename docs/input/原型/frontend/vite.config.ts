import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// 本地开发：/api 代理到网关，避免前端直连各微服务、规避跨域。
// 生产由统一门户 + 网关承载，前端以微前端形式嵌入。
export default defineConfig({
  plugins: [vue()],
  base: '/scholarsense/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_GATEWAY_URL || 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
});
