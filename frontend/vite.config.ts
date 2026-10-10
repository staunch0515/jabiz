/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { viteBase } from './src/base.ts'
import { appSettingsProblems } from './scripts/app-settings.ts'
import { extensionDir, extensionEntry } from './scripts/extension.ts'

const root = import.meta.dirname
const packageJson = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8')) as {
  dependencies: Record<string, string>
  devDependencies: Record<string, string>
}
// An application's admin pages (docs/design/12-frontend.md section 9, decision D22), compiled in when named.
const extension = extensionDir(process.env.JABIZ_ADMIN_EXTENSION, root)
// The application's interface languages and region (jabizApp { languages(...); region = ... }, decision D22 item 7).
const settingsProblems = appSettingsProblems(process.env)
if (settingsProblems.length > 0) throw new Error(settingsProblems.join('\n'))

/**
 * Tailwind generates the classes it finds in its sources: this project (packages/ui included) by default, and the
 * extension's pages, which lie outside it, when one is compiled in.
 */
function extensionStyles(dir: string | null): Plugin {
  return {
    name: 'jabiz-extension-styles',
    enforce: 'pre',
    transform(code, id) {
      if (!dir || !id.endsWith('/src/index.css')) return null
      return `${code}\n@source ${JSON.stringify(resolve(dir, 'src'))};\n`
    },
  }
}

export default defineConfig({
  // Served under a sub-path when an application puts its public website at the root (VITE_BASE=/admin/).
  base: viteBase(process.env.VITE_BASE),
  plugins: [extensionStyles(extension), tailwindcss(), react()],
  resolve: {
    alias: [
      { find: 'virtual:jabiz-extension', replacement: extensionEntry(extension, root) },
      { find: /^@jabiz\/admin$/, replacement: resolve(root, 'src/lib/index.ts') },
    ],
    // The extension lies outside this project and has no node_modules of its own: its imports of React, antd and
    // the rest (and its tests' of Testing Library) resolve to this project's copies: one React and one antd. The
    // workspace packages (@jabiz/client, @jabiz/ui) resolve their own dependencies; those shared with this project
    // (React, i18next, TanStack Query, lucide) are listed here and so stay one copy too.
    dedupe: [...Object.keys(packageJson.dependencies), ...Object.keys(packageJson.devDependencies)],
  },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
    fs: { allow: extension ? [root, extension] : [root] },
  },
  build: { chunkSizeWarningLimit: 3000 },
  test: {
    environment: 'jsdom',
    // Page tests drive whole forms through jsdom; with the packages' tests alongside, the slowest come near the
    // default 5 s on a loaded machine.
    testTimeout: 15_000,
    setupFiles: ['./src/test/setup.ts'],
    include: [
      'src/**/*.test.{ts,tsx}',
      'packages/*/src/**/*.test.{ts,tsx}',
      'scripts/**/*.test.ts',
      ...(extension ? [`${extension}/src/**/*.test.{ts,tsx}`] : []),
    ],
    css: false,
  },
})
