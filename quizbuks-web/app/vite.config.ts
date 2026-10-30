/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { jabizSpa } from '@jabiz/client/vite'
import { readFileSync } from 'node:fs'
import { defineConfig, searchForWorkspaceRoot } from 'vite'
import { loadBrand } from '../brands/brand.mjs'
import { qbBrand } from '../brands/vite.mjs'
import { buildTime } from './build-time.ts'

// The brand profile chosen by QB_BRAND (Q54): its name and colours in the manifest, its icons, colours and mark.
const brand = loadBrand()

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
    qbBrand({ brand, allow: [searchForWorkspaceRoot(process.cwd())] }),
    jabizSpa({
      pwa: {
        name: brand.name,
        shortName: brand.shortName,
        description: brand.description.en,
        themeColor: brand.themeColor,
        backgroundColor: brand.backgroundColor,
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
