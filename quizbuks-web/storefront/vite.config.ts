/// <reference types="vitest/config" />
import { defineConfig } from 'vite'

// The storefront's block (docs/quizbuks/shopify/S2b-ui.md section 2): one self-contained script, quiz.js, written into
// the Theme App Extension's assets (tools/quizbuks/shopify/extensions/quizbuks-quiz/assets), which the Shopify CLI
// deploys. Not committed: tools/quizbuks/shopify/deploy-extension.sh builds it first.
// The public site the "Powered by QuizBuks" line links to (S4a): SITE_URL at build time, as the site's own build.
const site = process.env.SITE_URL ?? 'https://quizbuks.com'
if (!/^https:\/\/[a-z0-9.-]+(:\d+)?$/.test(site)) throw new Error(`SITE_URL must be an https origin, was "${site}"`)

export default defineConfig({
  define: { __QUIZBUKS_SITE__: JSON.stringify(site) },
  build: {
    outDir: '../../tools/quizbuks/shopify/extensions/quizbuks-quiz/assets',
    emptyOutDir: false,
    target: 'es2022',
    // A classic script (the block's schema loads it deferred, not as a module).
    lib: { entry: 'src/quiz.ts', formats: ['iife'], name: 'QuizbuksQuiz', fileName: () => 'quiz.js' },
  },
  test: {
    environment: 'jsdom',
    testTimeout: 15_000,
    server: { deps: { inline: [/\/frontend\/node_modules\//] } },
  },
})
