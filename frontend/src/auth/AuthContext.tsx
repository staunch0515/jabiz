import { createContext, useCallback, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, refreshSession, unwrap } from '../api/client'
import { session } from '../api/session'

/**
 * How far a sign-in got (docs/design/10-security.md section 9): signed in, or a second factor (or its enrolment)
 * comes next under the challenge.
 */
export type SignInStep =
  | { status: 'SIGNED_IN' }
  | { status: 'MFA_REQUIRED' | 'MFA_ENROLLMENT_REQUIRED'; challenge: string }

interface AuthState {
  /** False until a stored session has been restored (or found missing). */
  ready: boolean
  signedIn: boolean
  userId: string | null
  permissions: ReadonlySet<string>
  /** After how many seconds without activity the session is locked (section 11); null until known. */
  idleTimeoutSeconds: number | null
  signIn(userName: string, password: string): Promise<SignInStep>
  /** The second step of a sign-in: a TOTP or recovery code under the challenge. */
  verify(challenge: string, code: string): Promise<void>
  signOut(): Promise<void>
  /** Whether the user holds the permission ("*" holds all). Only decides what the UI offers; the server checks. */
  can(permission: string): boolean
}

const AuthContext = createContext<AuthState | null>(null)

interface Identity {
  userId: string
  permissions: string[]
  idleTimeoutSeconds: number
}

const USER_NAME_KEY = 'jabiz.userName'

/** The name last signed in with in this tab, to fill in again after an idle lock (not a secret). */
// eslint-disable-next-line react-refresh/only-export-components
export function lastUserName(): string | undefined {
  try {
    return window.sessionStorage.getItem(USER_NAME_KEY) ?? undefined
  } catch {
    return undefined
  }
}

function rememberUserName(userName: string) {
  try {
    window.sessionStorage.setItem(USER_NAME_KEY, userName)
  } catch {
    // Only a convenience.
  }
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
      return {
        userId: answer.userId ?? '',
        permissions: answer.permissions ?? [],
        idleTimeoutSeconds: answer.idleTimeoutSeconds ?? 0,
      }
    },
  })
  const identity = token ? me.data ?? null : null

  const signIn = useCallback(
    async (userName: string, password: string): Promise<SignInStep> => {
      const answer = await unwrap(api.POST('/api/auth/login', { body: { userName, password } }))
      rememberUserName(userName)
      if (answer.status === 'MFA_REQUIRED' || answer.status === 'MFA_ENROLLMENT_REQUIRED') {
        return { status: answer.status, challenge: answer.challenge! }
      }
      session.store(answer.accessToken!, answer.refreshToken!)
      queryClient.clear()
      return { status: 'SIGNED_IN' }
    },
    [queryClient],
  )

  const verify = useCallback(
    async (challenge: string, code: string) => {
      const tokens = await unwrap(api.POST('/api/auth/challenge/verify', { body: { challenge, code } }))
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
      idleTimeoutSeconds: identity?.idleTimeoutSeconds || null,
      signIn,
      verify,
      signOut,
      can: (permission: string) => permissions.has('*') || permissions.has(permission),
    }
  }, [ready, token, identity, signIn, verify, signOut])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const state = useContext(AuthContext)
  if (!state) throw new Error('useAuth outside AuthProvider')
  return state
}
