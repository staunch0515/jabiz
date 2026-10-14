import { refreshSession, useAuth, useIdleLock } from '@jabiz/client'
import { useCallback } from 'react'

/**
 * Signs out after the entry's idle time (docs/design/10-security.md section 11; `jabiz.security.entries.<entry>.session
 * .idle-timeout`): 30 minutes in the sponsor console, never in the app (design default Q12). Activity keeps the
 * session alive on the server too. `onLock` runs just before signing out (to note why); signed out, the routes go to
 * the sign-in page themselves.
 */
export function useSessionLock(onLock?: () => void) {
  const { idleTimeoutSeconds, signOut } = useAuth()
  const lock = useCallback(() => {
    onLock?.()
    void signOut()
  }, [signOut, onLock])
  const keepAlive = useCallback(() => void refreshSession(), [])
  useIdleLock(idleTimeoutSeconds, lock, keepAlive)
}
