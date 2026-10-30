import type { Brand } from './brand.mjs'

/** A Vite plugin, typed without Vite (this folder has no dependencies of its own). */
export interface BrandPlugin {
  name: string
  config: () => {
    publicDir: string
    resolve: { alias: Record<string, string> }
    define: Record<string, string>
    server: { fs: { allow: string[] } }
  }
  transformIndexHtml: (html: string) => string
}

export function qbBrand(options?: { brand?: Brand; allow?: string[] }): BrandPlugin
