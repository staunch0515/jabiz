import type { ReactNode } from 'react'
import { CircleAlertIcon, SearchXIcon, TriangleAlertIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn, UI_SCOPE } from '../lib/utils'
import { Spinner } from './spinner'

export type PageStateKind = 'loading' | 'notFound' | 'error' | 'warning'

export interface PageStateProps {
  kind: PageStateKind
  /** The headline; a general one per kind when absent ("Not found", "Something went wrong", …). */
  title?: ReactNode
  /** The title's element: a heading when the state is all the page shows (`h1`), a paragraph by default. */
  titleAs?: 'h1' | 'h2' | 'p'
  /** What happened, in more words (the server's message). */
  description?: ReactNode
  /** What the user can do next (a button back). */
  action?: ReactNode
  className?: string
  'data-testid'?: string
}

const ICONS = { notFound: SearchXIcon, error: CircleAlertIcon, warning: TriangleAlertIcon } as const

/**
 * A page that cannot show its content (yet): loading, not found, failed, or a warning with a way on. Replaces
 * antd's `Spin` and `Result`. Errors and warnings are announced (`role="alert"`); loading is a polite status.
 */
export function PageState({ kind, title, titleAs: Title = 'p', description, action, className, ...rest }: PageStateProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  if (kind === 'loading') {
    return (
      <div
        data-slot="page-state"
        data-state="loading"
        className={cn(UI_SCOPE, 'flex min-h-40 items-center justify-center p-8', className)}
        {...rest}
      >
        <Spinner label={typeof title === 'string' ? title : undefined} showLabel={title !== undefined} />
      </div>
    )
  }
  const Icon = ICONS[kind]
  return (
    <div
      data-slot="page-state"
      data-state={kind}
      role={kind === 'notFound' ? undefined : 'alert'}
      className={cn(UI_SCOPE, 'flex flex-col items-center gap-3 p-8 text-center', className)}
      {...rest}
    >
      <Icon
        aria-hidden
        className={cn(
          UI_SCOPE,
          'size-10',
          kind === 'error' ? 'text-destructive' : kind === 'warning' ? 'text-warning' : 'text-muted-foreground',
        )}
      />
      <Title className="text-lg font-semibold">{title ?? t(`pageState.${kind}`)}</Title>
      {description && <div className="text-muted-foreground max-w-prose text-sm">{description}</div>}
      {action && <div className="mt-2 flex flex-wrap justify-center gap-2">{action}</div>}
    </div>
  )
}
