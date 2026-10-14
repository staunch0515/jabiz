/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { jabizSpa } from '@jabiz/client/vite'
import { defineConfig } from 'vite'

// The sponsor console (docs/quizbuks/plans/QP-pages.md): jabizApp { spa("/sponsor", "../../quizbuks-web/sponsor",
// entry = "sponsor") } builds it with VITE_BASE=/sponsor/ and VITE_JABIZ_ENTRY=sponsor; not a PWA.
export default defineConfig({
  plugins: [jabizSpa(), tailwindcss(), react()],
  server: { port: 5176, proxy: { '/api': 'http://localhost:8080' } },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    testTimeout: 15_000,
    server: { deps: { inline: [/\/frontend\/node_modules\//] } },
    css: false,
  },
})
