/**
 * Where the tokens live (docs/design/12-frontend.md section 4, decisions D15 and D43). The access token only in
 * memory: it is short-lived and a page script is the only thing that needs it. The refresh token under a key of the
 * frontend's sign-in entry (`jabiz.session.<entry>.refresh`), so that several single-page applications of one origin
 * keep sessions of their own. It is rotated on every refresh, and reusing a stolen one revokes the whole family on the
 * server (docs/design/10-security.md section 2).
 *
 * Where the refresh token is kept is the entry's `session.persistence` (decision D43 item 2): `tab` (the default) in
 * sessionStorage, so a reload keeps the session while closing the tab ends it; `device` in localStorage, so an
 * installed application stays signed in when opened again. The administration (`admin`) is never kept on the device,
 * whatever the server says, and neither is any entry in a browser without Web Locks: tabs sharing one stored token
 * must take turns refreshing it (see refreshSession), or the server takes the second use for a stolen token.
 *
 * A token kept on the device is also written to IndexedDB, and a refresh reads it from there (phase 16c-2): every tab
 * reads IndexedDB alike, whereas a tab's view of localStorage is a copy the browser updates from other tabs a little
 * later, so a tab that takes its turn right after another has rotated the token could still read the old one there
 * and send it again (Chromium does). localStorage keeps a copy for the synchronous checks ("is there a session").
 */
export type SessionPersistence = 'tab' | 'device'

/** The sign-in entry of the administration, and of a frontend that names none. */
export const ADMIN_ENTRY = 'admin'
/** Names of sign-in entries (docs/design/10-security.md section 15.1). */
export const ENTRY_NAME = /^[a-z][a-z0-9-]{0,39}$/
/** The key of the refresh token before sessions were kept per entry (line 1.2 before phase 16c); the admin's. */
const LEGACY_KEY = 'jabiz.refreshToken'

function buildEntry(): string | undefined {
  try {
    const env = (import.meta as { env?: Record<string, string | undefined> }).env
    return env?.VITE_JABIZ_ENTRY || undefined
  } catch {
    return undefined
  }
}

let entry = ADMIN_ENTRY
let persistence: SessionPersistence = 'tab'
let accessToken: string | null = null
const listeners = new Set<() => void>()

function notify() {
  listeners.forEach((l) => l())
}

function tabStorage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

function deviceStorage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

function read(store: Storage | null, key: string): string | null {
  try {
    return store?.getItem(key) ?? null
  } catch {
    return null
  }
}

function write(store: Storage | null, key: string, value: string | null) {
  try {
    if (value === null) store?.removeItem(key)
    else store?.setItem(key, value)
  } catch {
    // Without storage the session lasts as long as the page.
  }
}

/**
 * The shared copy of tokens kept on the device: what every tab reads alike (see the module's description). A record
 * holds the token, or `''` once the session ended; no record: never written (a token stored before this copy existed
 * is taken from localStorage). Replaceable for tests.
 */
export interface SharedTokens {
  get(key: string): Promise<string | undefined>
  put(key: string, value: string): Promise<void>
  delete(key: string): Promise<void>
}

function indexedDbTokens(): SharedTokens | null {
  if (typeof indexedDB === 'undefined') return null
  let opened: Promise<IDBDatabase> | null = null
  const db = () => {
    opened ??= new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('jabiz-session', 1)
      request.onupgradeneeded = () => request.result.createObjectStore('refresh')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    opened.catch(() => {
      opened = null
    })
    return opened
  }
  const run = <T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>) =>
    db().then((database) => new Promise<T>((resolve, reject) => {
      const transaction = database.transaction('refresh', mode)
      const request = work(transaction.objectStore('refresh'))
      // Committed, not only requested: the next tab's turn reads what this one wrote.
      transaction.oncomplete = () => resolve(request.result)
      transaction.onerror = () => reject(transaction.error)
      transaction.onabort = () => reject(transaction.error)
    }))
  return {
    get: (key) => run('readonly', (store) => store.get(key) as IDBRequest<string | undefined>),
    put: (key, value) => run('readwrite', (store) => store.put(value, key)).then(() => undefined),
    delete: (key) => run('readwrite', (store) => store.delete(key)).then(() => undefined),
  }
}

let shared: SharedTokens | null | undefined

function sharedTokens(): SharedTokens | null {
  if (shared === undefined) shared = indexedDbTokens()
  return shared
}

/** Uses `tokens` as the shared copy (tests); null: none, as in a browser without IndexedDB. */
export function setSharedTokens(tokens: SharedTokens | null) {
  shared = tokens
}

/**
 * Writes the shared copy. A copy that cannot be written must not stay behind either: it would be preferred to
 * localStorage, and the next refresh would send a token already rotated (and lose the session to reuse detection), or
 * an old sign-out marker would hide the new token. So a failed write removes the record, and reads fall back to
 * localStorage; a database that refuses even that is one whose reads fail too, which also falls back.
 */
async function share(key: string, value: string | null): Promise<void> {
  const tokens = sharedTokens()
  if (!tokens) return
  try {
    await tokens.put(key, value ?? '')
  } catch {
    try {
      await tokens.delete(key)
    } catch {
      // IndexedDB refused altogether (private mode of some browsers): localStorage alone.
    }
  }
}

/** Whether this browser can serialise the refreshes of several tabs (Web Locks; Safari 15.4 and later). */
export function hasWebLocks(): boolean {
  return typeof navigator !== 'undefined' && typeof navigator.locks?.request === 'function'
}

function refreshKey(name = entry): string {
  return `jabiz.session.${name}.refresh`
}

/** What a persistence asked for becomes here: only a non-admin entry, and only with Web Locks, keeps it on the device. */
function effective(asked: SessionPersistence): SessionPersistence {
  return asked === 'device' && entry !== ADMIN_ENTRY && hasWebLocks() ? 'device' : 'tab'
}

function store(which: SessionPersistence = persistence): Storage | null {
  return which === 'device' ? deviceStorage() : tabStorage()
}

/**
 * Takes up the stored session of the configured entry: the admin's from the key of before (once), and a token found
 * on the device as kept there (the server put it there by saying `device`; {@link session.setPersistence} confirms or
 * moves it once the entry's settings are read).
 */
function takeUp() {
  const key = refreshKey()
  if (entry === ADMIN_ENTRY) {
    const legacy = read(tabStorage(), LEGACY_KEY)
    if (legacy !== null) {
      if (read(tabStorage(), key) === null) write(tabStorage(), key, legacy)
      write(tabStorage(), LEGACY_KEY, null)
    }
  }
  const onDevice = read(deviceStorage(), key)
  if (onDevice !== null && effective('device') === 'tab') {
    // Never kept on the device here (the administration, or no Web Locks): it continues in this tab only.
    if (read(tabStorage(), key) === null) write(tabStorage(), key, onDevice)
    write(deviceStorage(), key, null)
  }
  persistence = effective(read(deviceStorage(), key) !== null ? 'device' : 'tab')
}

/**
 * Sets the sign-in entry of this frontend (decision D43 item 1): sessions, sign-in, refresh and identity providers are
 * the entry's. The default is the build's `VITE_JABIZ_ENTRY`, else `admin`. Call it before the first render.
 */
export function configureSession(name: string) {
  if (!ENTRY_NAME.test(name)) throw new Error(`Not a sign-in entry name: ${name}`)
  if (name !== entry) accessToken = null
  entry = name
  takeUp()
  notify()
}

{
  const built = buildEntry()
  if (built && ENTRY_NAME.test(built)) entry = built
  takeUp()
}

// Another tab of a session kept on the device signed out: so does this one (its access token is of the same session).
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (persistence === 'device' && event.key === refreshKey() && event.newValue === null && accessToken !== null) {
      accessToken = null
      notify()
    }
  })
}

/**
 * Runs work on the stored refresh token under the entry's Web Lock (`jabiz.refresh.<entry>`), where tabs share it: a
 * session kept on the device, or `always` (moving the token between storages). Tabs with a token of their own need
 * no lock. A browser that refuses the lock (SecurityError in sandboxed or opaque origins) runs the work unlocked:
 * such a page keeps nothing on the device anyway.
 */
export async function withRefreshLock<T>(work: () => Promise<T>, always = false): Promise<T> {
  if (!hasWebLocks() || (!always && persistence !== 'device')) return work()
  let started = false
  try {
    return await navigator.locks.request(`jabiz.refresh.${entry}`, () => {
      started = true
      return work()
    })
  } catch (error) {
    // The work's own failure is its caller's; only a refused lock falls back.
    if (started) throw error
    return work()
  }
}

export const session = {
  /** The sign-in entry of this frontend. */
  entry(): string {
    return entry
  },
  /** Where the refresh token is kept: `tab` or `device`. */
  persistence(): SessionPersistence {
    return persistence
  },
  /**
   * Keeps the refresh token as the entry's settings say (`GET /api/auth/entries/{name}`): moves a stored token to
   * the other storage when the setting differs from what was found.
   */
  /** {@link session.setPersistence} under the refresh lock, so that no other tab refreshes while the token moves. */
  applyPersistence(asked: SessionPersistence): Promise<void> {
    return withRefreshLock(async () => session.setPersistence(asked), true)
  },
  setPersistence(asked: SessionPersistence) {
    const next = effective(asked)
    if (next === persistence) return
    const key = refreshKey()
    const token = read(store(), key)
    write(store(), key, null)
    persistence = next
    if (token !== null) write(store(), key, token)
    void share(key, next === 'device' ? token : null)
  },
  accessToken(): string | null {
    return accessToken
  },
  refreshToken(): string | null {
    return read(store(), refreshKey())
  },
  /**
   * The refresh token to send now, read under the refresh lock: the shared copy of a token kept on the device (see
   * the module's description), else the stored one.
   */
  async lockedRefreshToken(): Promise<string | null> {
    const key = refreshKey()
    const local = read(store(), key)
    if (persistence !== 'device') return local
    let copy: string | undefined
    try {
      copy = await sharedTokens()?.get(key)
    } catch {
      copy = undefined
    }
    if (copy === undefined) return local
    // Another tab's turn may have changed it: localStorage follows.
    if ((copy || null) !== local) write(store(), key, copy || null)
    return copy || null
  },
  store(access: string, refresh: string) {
    accessToken = access
    write(store(), refreshKey(), refresh)
    if (persistence === 'device') void share(refreshKey(), refresh)
    notify()
  },
  /** As {@link session.store}, once the shared copy is written: before the refresh lock is released. */
  async storeShared(access: string, refresh: string): Promise<void> {
    accessToken = access
    write(store(), refreshKey(), refresh)
    if (persistence === 'device') await share(refreshKey(), refresh)
    notify()
  },
  /** A new access token for the same session (after a step-up, docs/design/10-security.md section 10). */
  storeAccess(access: string) {
    accessToken = access
    notify()
  },
  /** Ends the session here: the access token, and the entry's refresh token wherever it is kept. */
  clear() {
    accessToken = null
    write(tabStorage(), refreshKey(), null)
    write(deviceStorage(), refreshKey(), null)
    void share(refreshKey(), null)
    notify()
  },
  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
}
