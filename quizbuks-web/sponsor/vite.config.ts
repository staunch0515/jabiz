/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { jabizSpa } from '@jabiz/client/vite'
import { defineConfig, searchForWorkspaceRoot } from 'vite'
import { qbBrand } from '../brands/vite.mjs'

// The sponsor console (docs/quizbuks/plans/QP-pages.md): jabizApp { spa("/sponsor", "../../quizbuks-web/sponsor",
// entry = "sponsor") } builds it with VITE_BASE=/sponsor/ and VITE_JABIZ_ENTRY=sponsor; not a PWA. The brand profile chosen by
// QB_BRAND (Q54) gives its icons, colours, mark and name.
export default defineConfig({
  plugins: [qbBrand({ allow: [searchForWorkspaceRoot(process.cwd())] }), jabizSpa(), tailwindcss(), react()],
  server: { port: 5176, proxy: { '/api': 'http://localhost:8080' } },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    testTimeout: 15_000,
    server: { deps: { inline: [/\/frontend\/node_modules\//] } },
    css: false,
  },
})
