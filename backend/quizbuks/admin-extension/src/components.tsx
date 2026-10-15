import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ApiError,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  EXTENSION_NAMESPACE,
  PageState,
} from '@jabiz/admin'

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
