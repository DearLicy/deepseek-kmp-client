import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'

const local = path => fileURLToPath(new URL(path, import.meta.url))

export default defineConfig({
  base: process.env.BASE_PATH || './',
  resolve: { alias: { '@deepseek/shared': local('../.tmp/build/shared/dist/js/productionLibrary/DeepSeek-shared.mjs') } },
  cacheDir: local('../.tmp/web-vite'),
  server: { host: '127.0.0.1', port: 4173, strictPort: true, fs: { allow: [local('../')] } },
  build: { outDir: local('../.tmp/web-dist'), emptyOutDir: true, sourcemap: false },
})
