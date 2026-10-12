import type { AuthNavigation } from '@jabiz/ui'
import { useMemo } from 'react'
import { useHref, useNavigate } from 'react-router'

/**
 * The admin's router as the navigation of @jabiz/ui's sign-in blocks (decision D43; ROADMAP 16c-3): the blocks know
 * no router, so their links and moves go through React Router here, under the admin's base path.
 */
export function useRouterNavigation(): Required<AuthNavigation> {
  const navigate = useNavigate()
  const base = useHref('/')
  return useMemo(
    () => ({
      onNavigate: (path: string) => void navigate(path),
      hrefOf: (path: string) => base.replace(/\/$/, '') + path,
    }),
    [navigate, base],
  )
}
