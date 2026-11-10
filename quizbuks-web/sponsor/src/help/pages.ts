/*
 * The AI help's page names of the Sponsor pages (F-C10c): the server takes only the names of its list
 * (`help.HelpPages`, the entry `sponsor`) and drops any other; a path never leaves the page. A page renamed here is
 * renamed there too (`help.test.tsx` compares the two lists).
 */

/** The Sponsor pages' names on the server's list, in its order. */
export const SPONSOR_PAGE_NAMES = ['dashboard', 'quizzes', 'quiz-edit', 'publications', 'publication-edit',
  'publication-detail', 'ai', 'analytics', 'assets', 'shared-videos', 'video-uses', 'benefits', 'conversations',
  'notices', 'invitations', 'page', 'products', 'tree', 'topics', 'wallet', 'transactions', 'profile', 'account',
  'help'] as const

export type SponsorPageName = (typeof SPONSOR_PAGE_NAMES)[number]

/** The name of a page (`/quizzes/<id>` → `quiz-edit`, the asset library's products tab → `products`); null if none. */
export function sponsorPageName(path: string, search = ''): SponsorPageName | null {
  const [head, second, third] = path.split('?')[0].split('/').filter(Boolean)
  const tab = new URLSearchParams(search).get('tab')
  switch (head) {
    // F-F3's addresses first; the old ones (redirected by shell/legacy-paths.json) still name their pages.
    case 'home':
    case 'dashboard': return 'dashboard'
    case 'quizzes': return second ? 'quiz-edit' : 'quizzes'
    case 'publications':
      if (!second) return 'publications'
      if (second === 'map') return 'tree'
      if (second === 'stats') return 'analytics'
      if (third === 'analytics') return 'analytics'
      return second === 'new' ? 'publication-edit' : 'publication-detail'
    case 'stats': return 'analytics'
    case 'assets': return second === 'products' || tab === 'products' ? 'products' : 'assets'
    case 'benefits': return 'benefits'
    case 'messages': return second === 'conversations' || tab === 'conversations' ? 'conversations' : 'notices'
    case 'profile': return second === 'page' ? 'page' : 'profile'
    case 'tree': return 'tree'
    case 'money': return second === 'transactions' ? 'transactions' : 'wallet'
    case 'wallet': return 'wallet'
    case 'transactions': return 'transactions'
    case 'account': return 'account'
    case 'help': return 'help'
    default: return null
  }
}
