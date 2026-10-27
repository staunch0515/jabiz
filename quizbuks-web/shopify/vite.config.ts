/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { jabizSpa } from '@jabiz/client/vite'
import { defineConfig } from 'vite'

// QuizBuks for Shopify's embedded pages (docs/quizbuks/shopify/S1-install.md): jabizApp { spa("/shopify",
// "../../quizbuks-web/shopify", entry = "shopify") } builds them with VITE_BASE=/shopify/ and VITE_JABIZ_ENTRY=shopify;
// not a PWA. VITE_SHOPIFY_API_KEY (the app's public Client ID) goes into index.html for App Bridge.
export default defineConfig({
  plugins: [jabizSpa(), tailwindcss(), react()],
  server: { port: 5177, proxy: { '/api': 'http://localhost:8080' } },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    testTimeout: 15_000,
    server: { deps: { inline: [/\/frontend\/node_modules\//] } },
    css: false,
  },
})
