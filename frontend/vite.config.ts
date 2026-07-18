import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';


export default defineConfig({
  base: '/scholarsense/',
  plugins: [vue()],
  build: {
    manifest: true,
    sourcemap: false,
    target: 'es2022',
  },
});
