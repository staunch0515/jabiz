import { isProxyUnavailable, useServiceUnavailable } from '@jabiz/client'
import { useEffect } from 'react'

/** How the page goes to the maintenance page: reloading its own address (a test replaces it). */
export const maintenancePage = { open: () => window.location.reload() }

/**
 * A page open when the service goes into maintenance shows the maintenance page itself, as a fresh visit does: it
 * reloads its own address, and the reverse proxy answers with the maintenance page, which asks again every few seconds
 * and comes back to this address once the service answers (deploy/quizbuks/DEPLOY.md "维护模式").
 *
 * Only when that address does answer with the maintenance page: when the server cannot be reached at all, a reload
 * would get the app's shell from the Service Worker and fail again, so the platform's notice stays (17x).
 */
export function MaintenanceRedirect() {
  const unavailable = useServiceUnavailable()
  useEffect(() => {
    if (!unavailable) return
    let current = true
    fetch(window.location.href, { cache: 'no-store', credentials: 'same-origin' })
      .then((response) => {
        if (current && isProxyUnavailable(response)) maintenancePage.open()
      })
      .catch(() => {})
    return () => {
      current = false
    }
  }, [unavailable])
  return null
}
