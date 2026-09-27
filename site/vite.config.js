import { defineConfig } from 'vite'

// The public site is served at the root of the application jar; the build sets VITE_BASE (docs/design/17 section 3).
export default defineConfig({
  base: process.env.VITE_BASE ?? '/',
})
