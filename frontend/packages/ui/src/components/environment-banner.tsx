import { useEnvironment, type Environment } from '@jabiz/client'
import { TriangleAlert } from 'lucide-react'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { UI_NAMESPACE } from '../i18n'
import { cn } from '../lib/utils'

export interface EnvironmentBannerProps {
  /** The environment to show; the one the server tells (`useEnvironment`) when absent. */
  environment?: Environment
  className?: string
}

/** Puts the environment's name in front of the document's title while the banner is shown. */
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
 * Says, loudly, that this is a test system and not the real one (platform phase 16zd): a full-width stripe with the
 * environment's name, on every page of a frontend, before and after signing in; the document's title says it too.
 * Nothing at all on production, and nothing while the environment is not known yet. `AppShell` and `AuthFrame` render
 * it themselves; a frontend with a frame of its own puts it at the top of that frame.
 */
export function EnvironmentBanner({ environment, className }: EnvironmentBannerProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const known = useEnvironment()
  const shown = environment ?? known
  const test = shown && !shown.production ? shown : undefined
  useTitlePrefix(test?.name)
  if (!test) return null
  return (
    <div
      role="note"
      aria-label={t('environment.label')}
      data-slot="environment-banner"
      data-testid="environment-banner"
      data-environment={test.name}
      className={cn(
        'bg-warning text-warning-foreground flex w-full items-center justify-center gap-2 px-3 py-1.5 text-center',
        'text-sm font-bold tracking-wide',
        className,
      )}
    >
      <TriangleAlert aria-hidden="true" className="size-4 shrink-0" />
      <span className="uppercase">{test.name}</span>
      <span aria-hidden="true">·</span>
      <span>{t('environment.notProduction')}</span>
    </div>
  )
}
