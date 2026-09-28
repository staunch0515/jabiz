/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { firstScreenBudget } from './scripts/check-bundle.mjs'

// The public site is served at the root of the application jar; the build sets VITE_BASE (docs/design/17 section 3).
export default defineConfig({
  base: process.env.VITE_BASE ?? '/',
  plugins: [react(), firstScreenBudget()],
  build: {
    rolldownOptions: {
      output: {
        // The site's own shared modules travel together with the first screen instead of as a dozen tiny requests;
        // the Markdown renderer and the map stay in chunks of their own, loaded when needed (design section 9.4).
        codeSplitting: {
          groups: [
            {
              name: 'site',
              test: (id: string) =>
                /\/src\/(components|lib|i18n|api)\//.test(id) && !/(MarkdownContent|\/lib\/map)\.tsx?$/.test(id),
            },
          ],
        },
      },
    },
  },
  server: { proxy: { '/api': 'http://localhost:8080' } },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: { modules: { classNameStrategy: 'non-scoped' } },
  },
})
