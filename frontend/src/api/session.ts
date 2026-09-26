/**
 * Where the tokens live (docs/design/12-frontend.md section 4, decision D15). The access token only in memory: it is
 * short-lived and a page script is the only thing that needs it. The refresh token in sessionStorage, so a reload
 * keeps the session while closing the tab ends it; it is rotated on every refresh, and reusing a stolen one revokes
 * the whole family on the server (docs/design/10-security.md section 2).
 */
const REFRESH_KEY = 'jabiz.refreshToken'

let accessToken: string | null = null
const listeners = new Set<() => void>()

function storage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

export const session = {
  accessToken(): string | null {
    return accessToken
  },
  refreshToken(): string | null {
    try {
      return storage()?.getItem(REFRESH_KEY) ?? null
    } catch {
      return null
    }
  },
  store(access: string, refresh: string) {
    accessToken = access
    try {
      storage()?.setItem(REFRESH_KEY, refresh)
    } catch {
      // Without storage the session lasts as long as the page.
    }
    listeners.forEach((l) => l())
  },
  clear() {
    accessToken = null
    try {
      storage()?.removeItem(REFRESH_KEY)
    } catch {
      // nothing stored
    }
    listeners.forEach((l) => l())
  },
  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
}
