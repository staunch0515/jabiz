/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import { loadBrand } from '../brands/brand.mjs'

// The storefront's block (docs/quizbuks/shopify/S2b-ui.md section 2): self-contained scripts, written into
// the Theme App Extension's assets (tools/quizbuks/shopify/extensions/quizbuks-quiz/assets), which the Shopify CLI
// deploys. Not committed: tools/quizbuks/shopify/deploy-extension.sh builds them first.
// The brand profile QB_BRAND chooses (Q54): the "Powered by" line names it and links to its public site (S4a),
// unless SITE_URL says otherwise, as the site's own build.
const brand = loadBrand()
const site = process.env.SITE_URL ?? brand.siteUrl
if (!/^https:\/\/[a-z0-9.-]+(:\d+)?$/.test(site)) throw new Error(`SITE_URL must be an https origin, was "${site}"`)

// Two scripts (S4c), one build each (a classic script cannot be split): quiz.js, the block's own (`vite build`), and
// quiz-player.js, the quiz it loads when first opened (`vite build --mode player`).
export default defineConfig(({ mode }) => ({
  define: { __QUIZBUKS_SITE__: JSON.stringify(site), __QUIZBUKS_BRAND__: JSON.stringify(brand.name) },
  build: {
    outDir: '../../tools/quizbuks/shopify/extensions/quizbuks-quiz/assets',
    emptyOutDir: false,
    target: 'es2022',
    // Classic scripts (the block's schema loads quiz.js deferred, not as a module; quiz.js adds quiz-player.js).
    lib: mode === 'player'
      ? { entry: 'src/player.ts', formats: ['iife'], name: 'QuizbuksQuizPlayer', fileName: () => 'quiz-player.js' }
      : { entry: 'src/quiz.ts', formats: ['iife'], name: 'QuizbuksQuiz', fileName: () => 'quiz.js' },
  },
  test: {
    environment: 'jsdom',
    testTimeout: 15_000,
    server: { deps: { inline: [/\/frontend\/node_modules\//] } },
  },
}))
