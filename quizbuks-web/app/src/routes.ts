/** The pages of one publication or attempt: `/p/<publication>`, `/play/<attempt>`, `/result/<attempt>`. */
const ITEM_PAGE = /^\/(p|play|result)\/([^/]+)$/

/** The page and its item of a path such as `/play/<attempt>`, or null for any other path. */
export function itemPage(path: string): { page: 'p' | 'play' | 'result'; id: string } | null {
  const match = ITEM_PAGE.exec(path)
  if (!match) return null
  try {
    return { page: match[1] as 'p' | 'play' | 'result', id: decodeURIComponent(match[2]) }
  } catch {
    // A malformed escape: no such page.
    return null
  }
}
