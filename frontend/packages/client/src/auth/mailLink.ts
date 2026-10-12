import { useEffect, useState } from 'react'
import { ENTRY_NAME, session } from '../api/session'
import { entryInfo } from './account'

/**
 * A mailed link (`{entryUrl}auth/<page>?entry=<entry>&token=<token>`, decision D36 implementation note 13 and D43
 * item 4): its sign-in entry, its token and the last segment of its path (`verify-email`, `join`, `password-reset`).
 */
export interface MailLink {
  /** The entry named by the link when it is a proper name; else this frontend's. */
  entry: string
  token: string | null
  /** The last segment of the path: which page of the link this is. */
  page: string
}

/**
 * Reads a mailed link and takes its token out of the address bar (`history.replaceState`), so that the token stays
 * out of the history, bookmarks and screenshots. `search` is the query to read when a router keeps a location of its
 * own; the address bar is cleaned either way. Pages also send no referrer while open ({@link useNoReferrer}).
 */
export function readMailLink(search?: string): MailLink {
  const link = parseMailLink(search)
  stripToken()
  return link
}

/** Reads a mailed link without touching the address bar. */
export function parseMailLink(search?: string): MailLink {
  const params = new URLSearchParams(search ?? window.location.search)
  const raw = params.get('entry')
  const segments = window.location.pathname.split('/').filter(Boolean)
  return {
    entry: raw && ENTRY_NAME.test(raw) ? raw : session.entry(),
    token: params.get('token') || null,
    page: segments[segments.length - 1] ?? '',
  }
}

function stripToken() {
  const url = new URL(window.location.href)
  if (url.searchParams.has('token')) {
    url.searchParams.delete('token')
    window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash)
  }
}

/** No referrer while the page is open: its address held a token, and its links lead elsewhere. */
export function useNoReferrer() {
  useEffect(() => {
    const meta = document.createElement('meta')
    meta.name = 'referrer'
    meta.content = 'no-referrer'
    document.head.appendChild(meta)
    return () => meta.remove()
  }, [])
}

/**
 * Only a path of this site, else `fallback`. Resolved rather than matched: URL parsers drop tabs and line breaks and
 * read backslashes as slashes, so `/\t/evil.com` would pass a prefix check and still lead to another site. Control
 * characters and backslashes are refused outright; what is left must resolve to this origin.
 */
export function sitePath(path: string | null | undefined, fallback = '/'): string {
  // eslint-disable-next-line no-control-regex
  if (!path || !path.startsWith('/') || /[\u0000-\u001f\u007f\\]/.test(path)) return fallback
  try {
    const origin = window.location.origin
    const url = new URL(path, origin)
    return url.origin === origin ? url.pathname + url.search + url.hash : fallback
  } catch {
    return fallback
  }
}

/**
 * The mailed link of the page (see {@link readMailLink}), read once per `search`, with no referrer while the page is
 * open, and the root path of the entry's frontend (`app-path`): where the page leads once done.
 */
export function useMailLink(search?: string): MailLink & { appPath: string } {
  // Read while rendering, without side effects, once per search; the address bar loses the token right after.
  const [read, setRead] = useState(() => ({ search, link: parseMailLink(search) }))
  let current = read
  if (read.search !== search) {
    current = { search, link: parseMailLink(search) }
    setRead(current)
  }
  const { link } = current
  useEffect(stripToken, [search])
  useNoReferrer()
  const [appPath, setAppPath] = useState('/')
  useEffect(() => {
    let live = true
    entryInfo(link.entry)
      .then((info) => {
        if (live) setAppPath(sitePath(info.appPath))
      })
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [link.entry])
  return { ...link, appPath }
}
