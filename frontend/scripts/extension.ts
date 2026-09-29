import { existsSync } from 'node:fs'
import { isAbsolute, resolve } from 'node:path'

/**
 * Build-time resolution of the application's admin extension (decision D22), shared by vite.config.ts and the
 * extension scripts. The extension is a directory with `src/index.tsx` (or `.ts`) exporting its definition.
 */

/** The extension directory for a `JABIZ_ADMIN_EXTENSION` value (relative to this project), or null when unset. */
export function extensionDir(value: string | undefined, root: string): string | null {
  if (value === undefined || value.trim() === '') return null
  const dir = isAbsolute(value) ? value : resolve(root, value)
  if (!existsSync(dir)) throw new Error(`JABIZ_ADMIN_EXTENSION: no directory ${dir}`)
  return dir
}

/** The module the extension is loaded from: its entry, or the empty extension. */
export function extensionEntry(dir: string | null, root: string): string {
  if (dir === null) return resolve(root, 'src/extension/none.ts')
  for (const name of ['src/index.tsx', 'src/index.ts']) {
    const entry = resolve(dir, name)
    if (existsSync(entry)) return entry
  }
  throw new Error(`JABIZ_ADMIN_EXTENSION: ${dir} has no src/index.tsx or src/index.ts`)
}
