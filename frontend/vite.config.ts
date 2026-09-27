/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteBase } from './src/base.ts'

export default defineConfig({
  // Served under a sub-path when an application puts its public website at the root (VITE_BASE=/admin/).
  base: viteBase(process.env.VITE_BASE),
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8080' } },
  build: { chunkSizeWarningLimit: 3000 },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
})
