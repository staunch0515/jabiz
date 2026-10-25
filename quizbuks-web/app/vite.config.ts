/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { jabizSpa } from '@jabiz/client/vite'
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import { buildTime } from './build-time.ts'

// The About page (U-74) shows the app's version (package.json) and when it was built (the sources' time: build-time.ts).
const version = (JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string })
  .version

// The takers' app (docs/quizbuks/plans/QP-pages.md): jabizApp { spa("/taker", "../../quizbuks-web/app", entry = "app",
// pwa = Pwa.ON) } builds it with VITE_BASE=/taker/, VITE_JABIZ_ENTRY=app, VITE_JABIZ_PWA=on and the other SPAs' prefixes
// (/sponsor/, /admin/, /biz/), which its Service Worker leaves alone; the preset takes them from there (Q30 moved the
// app from the root; the server redirects the old addresses).
export default defineConfig({
  define: {
    __QB_APP_VERSION__: JSON.stringify(version),
    __QB_APP_BUILT_AT__: JSON.stringify(buildTime()),
  },
  plugins: [
    jabizSpa({
      pwa: {
        name: 'QuizBuks',
        shortName: 'QuizBuks',
        description: 'Answer quizzes, earn Kudos',
        themeColor: '#047857',
        backgroundColor: '#ffffff',
        icons: [
          { src: 'icon-192.png', sizes: '192x192' },
          { src: 'icon-512.png', sizes: '512x512' },
          { src: 'icon-maskable-512.png', sizes: '512x512', purpose: 'maskable' },
        ],
      },
    }),
    tailwindcss(),
    react(),
  ],
  server: { port: 5175, proxy: { '/api': 'http://localhost:8080' } },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    testTimeout: 15_000,
    server: { deps: { inline: [/\/frontend\/node_modules\//] } },
    css: false,
  },
})
