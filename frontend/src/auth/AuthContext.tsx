import { createContext, useCallback, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, refreshSession, unwrap } from '../api/client'
import { session } from '../api/session'

interface AuthState {
  /** False until a stored session has been restored (or found missing). */
  ready: boolean
  signedIn: boolean
  userId: string | null
  permissions: ReadonlySet<string>
  signIn(userName: string, password: string): Promise<void>
  signOut(): Promise<void>
  /** Whether the user holds the permission ("*" holds all). Only decides what the UI offers; the server checks. */
  can(permission: string): boolean
}

const AuthContext = createContext<AuthState | null>(null)

interface Identity {
  userId: string
  permissions: string[]
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const token = useSyncExternalStore(session.subscribe, session.accessToken, () => null)
  const [ready, setReady] = useState(false)

  // A reload keeps the refresh token (sessionStorage) but not the access token: restore the session once.
  useEffect(() => {
    let cancelled = false
    const restore = session.refreshToken() && !session.accessToken() ? refreshSession() : Promise.resolve(false)
    void restore.finally(() => {
      if (!cancelled) setReady(true)
    })
    return () => {
      cancelled = true
    }
  }, [])

  // Who the token belongs to: asked from the server rather than decoded, so the UI never trusts unverified claims.
  const me = useQuery({
    queryKey: ['auth', 'identity', token],
    enabled: !!token,
    staleTime: Infinity,
    queryFn: async (): Promise<Identity> => {
      const answer = await unwrap(api.GET('/api/auth/me'))
      return { userId: answer.userId ?? '', permissions: answer.permissions ?? [] }
    },
  })
  const identity = token ? me.data ?? null : null

  const signIn = useCallback(
    async (userName: string, password: string) => {
      const tokens = await unwrap(api.POST('/api/auth/login', { body: { userName, password } }))
      session.store(tokens.accessToken!, tokens.refreshToken!)
      queryClient.clear()
    },
    [queryClient],
  )

  const signOut = useCallback(async () => {
    const refreshToken = session.refreshToken()
    session.clear()
    queryClient.clear()
    if (refreshToken) {
      try {
        await api.POST('/api/auth/logout', { body: { refreshToken } })
      } catch {
        // The local session is gone either way.
      }
    }
  }, [queryClient])

  const value = useMemo<AuthState>(() => {
    const permissions = new Set(identity?.permissions ?? [])
    return {
      ready,
      signedIn: !!token,
      userId: identity?.userId ?? null,
      permissions,
      signIn,
      signOut,
      can: (permission: string) => permissions.has('*') || permissions.has(permission),
    }
  }, [ready, token, identity, signIn, signOut])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const state = useContext(AuthContext)
  if (!state) throw new Error('useAuth outside AuthProvider')
  return state
}
