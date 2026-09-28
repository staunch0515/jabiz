import type { LocalizedText } from './public-queries'
import { usePublicQuery } from './hooks'

/** Texts of the fixed pages by key (design section 3.9): editors change them in the admin, not in code. */
export function useSiteBlocks(keys: readonly string[]) {
  const result = usePublicQuery('culture.public.site_blocks', { keys }, { limit: 100 })
  const blocks: Record<string, LocalizedText | null> = {}
  for (const row of result.data ?? []) {
    if (row.blockKey) blocks[row.blockKey] = row.body
  }
  return { ...result, blocks }
}
