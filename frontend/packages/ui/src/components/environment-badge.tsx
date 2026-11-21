import { useEnvironment, type Environment } from '@jabiz/client'
import { TriangleAlert, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { UI_NAMESPACE } from '../i18n'
import { cn } from '../lib/utils'

export interface EnvironmentBadgeProps {
  /** The environment to show; the one the server tells (`useEnvironment`) when absent. */
  environment?: Environment
  className?: string
}

/** Where the badge's collapsed state is kept: per tab, not across them (a new tab shows the mark again). */
const COLLAPSED_KEY = 'jabiz.environment-badge.collapsed'

function readCollapsed(): boolean {
  try {
    return window.sessionStorage.getItem(COLLAPSED_KEY) === '1'
  } catch {
    // Storage may be blocked (private windows, previews): the mark is shown.
    return false
  }
}

function keepCollapsed(collapsed: boolean) {
  try {
    if (collapsed) window.sessionStorage.setItem(COLLAPSED_KEY, '1')
    else window.sessionStorage.removeItem(COLLAPSED_KEY)
  } catch {
    // Not kept: the page still works.
  }
}

/** Puts the environment's name in front of the document's title while the badge is shown. */
function useTitlePrefix(name: string | undefined) {
  useEffect(() => {
    if (!name) return
    const prefix = `[${name.toUpperCase()}] `
    const apply = () => {
      if (!document.title.startsWith(prefix)) document.title = prefix + document.title
    }
    apply()
    // The pages set their own titles after this; the head is watched (a <title> may only appear with the first).
    const observer = new MutationObserver(apply)
    observer.observe(document.head, { childList: true, characterData: true, subtree: true })
    return () => {
      observer.disconnect()
      if (document.title.startsWith(prefix)) document.title = document.title.slice(prefix.length)
    }
  }, [name])
}

/**
 * Says that this is a test system and not the real one (platform phases 16zd and 17zz27) without taking any room of
 * the page: a small, slightly see-through capsule (90 %: enough for text to keep its contrast on a white page) fixed at the bottom left (the bottom right is where a video's full-screen
 * key and the usual action buttons are), with the environment's name; a click on its close button folds it into a
 * small dot, a click on the dot opens it again, and the choice is kept for the tab only (`sessionStorage`). The
 * document's title says it too. Nothing at all on production, and nothing while the environment is not known yet.
 * `AppShell` and `AuthFrame` render it themselves; a frontend with a frame of its own puts it in that frame (it is
 * fixed, so anywhere will do).
 */
export function EnvironmentBadge({ environment, className }: EnvironmentBadgeProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const known = useEnvironment()
  const shown = environment ?? known
  const test = shown && !shown.production ? shown : undefined
  const [collapsed, setCollapsed] = useState(readCollapsed)
  useTitlePrefix(test?.name)
  if (!test) return null
  const set = (next: boolean) => {
    setCollapsed(next)
    keepCollapsed(next)
  }
  const message = t('environment.notProduction')
  return (
    <div
      role="note"
      aria-label={t('environment.label')}
      data-slot="environment-badge"
      data-testid="environment-badge"
      data-environment={test.name}
      data-collapsed={collapsed ? 'true' : 'false'}
      title={message}
      // Under the dialogs (z-50), over the page; the bottom edge keeps clear of the phones' home indicator.
      className={cn(
        'pointer-events-none fixed bottom-[max(0.75rem,env(safe-area-inset-bottom))] left-3 z-40 print:hidden',
        className,
      )}
    >
      {collapsed ? (
        <button
          type="button"
          data-testid="environment-badge-expand"
          aria-label={`${t('environment.expand')}: ${test.name}`}
          onClick={() => set(false)}
          className={cn(
            'bg-warning/90 text-warning-foreground pointer-events-auto flex size-6 items-center justify-center',
            'rounded-full shadow-sm backdrop-blur focus-visible:outline-2 focus-visible:outline-offset-2',
          )}
        >
          <TriangleAlert aria-hidden="true" className="size-3.5" />
        </button>
      ) : (
        <div
          className={cn(
            'bg-warning/90 text-warning-foreground pointer-events-auto flex items-center gap-1.5 rounded-full',
            'py-0.5 pr-1 pl-2.5 text-xs font-bold tracking-wide shadow-sm backdrop-blur',
          )}
        >
          <TriangleAlert aria-hidden="true" className="size-3.5 shrink-0" />
          <span className="uppercase">{test.name}</span>
          <span className="sr-only">{message}</span>
          <button
            type="button"
            data-testid="environment-badge-collapse"
            aria-label={t('environment.collapse')}
            onClick={() => set(true)}
            className="flex size-5 items-center justify-center rounded-full hover:bg-black/10 focus-visible:outline-2"
          >
            <X aria-hidden="true" className="size-3" />
          </button>
        </div>
      )}
    </div>
  )
}
