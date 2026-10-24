/**
 * The pages of one publication or attempt: `/p/<publication>`, `/play/<attempt>`, `/result/<attempt>`; and the page
 * a scanned redeem code opens (Q22b): `/redeem/<key>`.
 */
const ITEM_PAGE = /^\/(p|play|result|redeem)\/([^/]+)$/

export type ItemPage = 'p' | 'play' | 'result' | 'redeem'

/** The page and its item of a path such as `/play/<attempt>`, or null for any other path. */
export function itemPage(path: string): { page: ItemPage; id: string } | null {
  const match = ITEM_PAGE.exec(path)
  if (!match) return null
  try {
    return { page: match[1] as ItemPage, id: decodeURIComponent(match[2]) }
  } catch {
    // A malformed escape: no such page.
    return null
  }
}
