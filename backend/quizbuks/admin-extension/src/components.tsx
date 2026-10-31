import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  EXTENSION_NAMESPACE,
  PageState,
  UserName,
  useAuth,
} from '@jabiz/admin'
import { EmptyArt, type ArtKind } from './art'
import { PATH, PERMISSION, SPONSOR_KIND } from './ids'
import { statusTone } from './status'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/**
 * Who published (S0): a sponsor is a user with a page, linked and named (`label`, else their name); a publisher of
 * another kind has no user page here and shows its label or id.
 */
export function PublisherLink({ kind, id, label, testId }: {
  kind: string | null | undefined
  id: string
  label?: ReactNode | null
  testId?: string
}) {
  const { can } = useAuth()
  if (!kind || kind === SPONSOR_KIND) {
    // Q48a: an operator reads no user's summary; the sponsor's page is the one it has.
    const operator = !can(PERMISSION.usersRead) && can(PERMISSION.operatorUse)
    return (
      <Link to={operator ? PATH.sponsor(id) : PATH.user(id)} className={linkClass} data-testid={testId}>
        {label ?? <UserName id={id} />}
      </Link>
    )
  }
  return <span data-testid={testId}>{label ?? <code className="font-mono">{id}</code>}</span>
}

/** A titled card of a page (its heading is the section's `<h2>`). */
export function Section({ title, children, testId, actions }: {
  title: ReactNode
  children: ReactNode
  testId?: string
  actions?: ReactNode
}) {
  return (
    <Card data-testid={testId}>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle>
          <h2 className="text-base font-semibold">{title}</h2>
        </CardTitle>
        {actions}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  )
}

/** A failed read: the server's own words when it said why (a refusal), with a retry. */
export function LoadFailed({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <PageState
      kind="error"
      description={error instanceof ApiError && error.status !== 500 ? error.display : t('common.failed')}
      action={onRetry && <Button variant="outline" onClick={onRetry}>{t('common.retry')}</Button>}
    />
  )
}

/** What follows a decision: waiting for the state, not updated after the wait (read again), or only a level approved. */
export function AfterDecision({ after, onRetry }: {
  after: { levelOnly: boolean; expired: boolean }
  onRetry: () => void
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  if (after.levelOnly) {
    return <Alert data-testid="decision-level"><AlertDescription>{t('decision.levelApproved')}</AlertDescription></Alert>
  }
  if (after.expired) {
    return (
      <Alert variant="warning" data-testid="decision-not-updated">
        <AlertDescription className="flex flex-wrap items-center gap-2">
          {t('decision.notUpdated')}
          <Button variant="outline" size="sm" onClick={onRetry} data-testid="decision-reload">
            {t('decision.reload')}
          </Button>
        </AlertDescription>
      </Alert>
    )
  }
  return <Alert data-testid="decision-updating"><AlertDescription>{t('decision.updating')}</AlertDescription></Alert>
}

/**
 * A status as a badge coloured by what it means (Q53c, `statusTone`): green done, amber waiting, red refused or
 * failed, none when over. `label` is the dictionary's text; the code stays on the element for tests.
 */
export function StatusBadge({ code, label, testId, className }: { code: string | null | undefined;
  label: ReactNode; testId?: string; className?: string }) {
  return (
    <Badge variant={statusTone(code)} className={className} data-testid={testId} data-status={code ?? undefined}>
      {label}
    </Badge>
  )
}

/** What an empty list shows (Q53c): a small drawing, a line saying so and, when it helps, what to do. */
export function EmptyState({ title, hint, art = 'inbox', testId }: { title: ReactNode; hint?: ReactNode;
  art?: ArtKind; testId?: string }) {
  return (
    <div className="flex flex-col items-center gap-2 py-6 text-center" data-testid={testId ?? 'empty-state'}>
      <EmptyArt kind={art} />
      <p className="text-sm font-medium">{title}</p>
      {hint && <p className="text-muted-foreground max-w-md text-xs">{hint}</p>}
    </div>
  )
}
