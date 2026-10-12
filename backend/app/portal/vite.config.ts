/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { jabizSpa } from '@jabiz/client/vite'
import { defineConfig } from 'vite'

// The customer portal (docs/guide/app-spa.md): jabizApp { spa("/portal", "portal", entry = "portal", pwa = Pwa.ON) }
// builds it with VITE_BASE=/portal/, VITE_JABIZ_ENTRY=portal and VITE_JABIZ_PWA=on; the preset takes them from there.
export default defineConfig({
  plugins: [
    jabizSpa({
      pwa: {
        name: 'jabiz customer portal',
        shortName: 'Portal',
        description: 'Your orders',
        themeColor: '#0f172a',
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
  server: { port: 5174, proxy: { '/api': 'http://localhost:8080' } },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    testTimeout: 15_000,
    server: { deps: { inline: [/\/frontend\/node_modules\//] } },
    css: false,
  },
})
