import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react'

/** Light, dark, or as the operating system says (decision D34 item 3). */
export type Appearance = 'light' | 'dark' | 'system'

export const APPEARANCES: readonly Appearance[] = ['light', 'dark', 'system']

const STORAGE_KEY = 'jabiz.appearance'
const DARK_QUERY = '(prefers-color-scheme: dark)'

/**
 * The appearance the user chose in this browser; "system" when none was chosen, or when storage is not available
 * (private windows, blocked site data): a convenience, never needed for the page to work.
 */
export function storedAppearance(): Appearance {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY)
    return APPEARANCES.includes(value as Appearance) ? (value as Appearance) : 'system'
  } catch {
    return 'system'
  }
}

function storeAppearance(appearance: Appearance) {
  try {
    if (appearance === 'system') window.localStorage.removeItem(STORAGE_KEY)
    else window.localStorage.setItem(STORAGE_KEY, appearance)
  } catch {
    // Remembered for this page only.
  }
}

function systemIsDark(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia(DARK_QUERY).matches
}

/** The appearance shown for a choice: "system" resolved to light or dark. */
export function resolveAppearance(appearance: Appearance): 'light' | 'dark' {
  if (appearance === 'system') return systemIsDark() ? 'dark' : 'light'
  return appearance
}

/** Sets the `.dark` class on <html> (the theme's dark tokens hang from it); returns what is shown. */
export function applyAppearance(appearance: Appearance = storedAppearance()): 'light' | 'dark' {
  const resolved = resolveAppearance(appearance)
  if (typeof document !== 'undefined') document.documentElement.classList.toggle('dark', resolved === 'dark')
  return resolved
}

interface AppearanceState {
  appearance: Appearance
  resolved: 'light' | 'dark'
  setAppearance: (appearance: Appearance) => void
}

const AppearanceContext = createContext<AppearanceState | null>(null)

function subscribeToSystem(onChange: () => void) {
  if (typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(DARK_QUERY)
  query.addEventListener?.('change', onChange)
  return () => query.removeEventListener?.('change', onChange)
}

/** Keeps <html> in the chosen appearance, following the system's while that is the choice. */
export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [appearance, setChoice] = useState<Appearance>(storedAppearance)
  const systemDark = useSyncExternalStore(subscribeToSystem, systemIsDark, () => false)
  const resolved: 'light' | 'dark' = appearance === 'system' ? (systemDark ? 'dark' : 'light') : appearance

  useEffect(() => {
    document.documentElement.classList.toggle('dark', resolved === 'dark')
  }, [resolved])

  const setAppearance = useCallback((next: Appearance) => {
    storeAppearance(next)
    setChoice(next)
  }, [])

  const value = useMemo(() => ({ appearance, resolved, setAppearance }), [appearance, resolved, setAppearance])
  return <AppearanceContext.Provider value={value}>{children}</AppearanceContext.Provider>
}

export function useAppearance(): AppearanceState {
  const state = useContext(AppearanceContext)
  if (!state) throw new Error('useAppearance needs an <AppearanceProvider> around it')
  return state
}
