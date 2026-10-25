import { resolve } from 'node:path'
import { defineConfig } from 'vite'

// The business site (docs/quizbuks/plans/Q30-paths.md): three static pages, one per language, under /biz/ (jabizApp
// { spa("/biz", "../../quizbuks-web/biz") } builds it with VITE_BASE=/biz/). No script runs on it: the pages are plain
// HTML and one stylesheet, so the platform's default content security policy holds.
const base = process.env.VITE_BASE ?? '/biz/'
if (!/^\/[a-z0-9-]+\/$/.test(base)) throw new Error(`VITE_BASE must be like "/biz/", was "${base}"`)

export default defineConfig({
  base,
  build: {
    rollupOptions: {
      input: {
        en: resolve(import.meta.dirname, 'index.html'),
        zh: resolve(import.meta.dirname, 'zh.html'),
        ja: resolve(import.meta.dirname, 'ja.html'),
      },
    },
  },
  server: { port: 5177 },
})
