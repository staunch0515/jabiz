import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  ConfirmDialog,
  DataTable,
  DatePicker,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  notify,
  Spinner,
  Textarea,
  UI_NAMESPACE,
  type ColumnDef,
} from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { Info } from 'lucide-react'
import { useId, useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { api, unwrap } from '../api/client'
import type { components } from '@jabiz/client'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import UserName from '../components/UserName'
import { useLocalPage } from '../components/useLocalPage'
import AdminPage from '../layout/AdminPage'
import { runProcess } from '../lib/calls'
import { formatDateTime } from '../meta/format'
import { paths } from './paths'

type Review = components['schemas']['Review']
type Conflict = components['schemas']['Conflict']
type Change = components['schemas']['AuditRecordEntry']

/** The access report (docs/design/10-security.md section 13.3). */
export const ACCESS_TEMPLATE = 'jabiz.security.access_review'
export const SIGN_OFF = 'ACCESS_REVIEW_SIGN_OFF'
const MAX_COMMENT = 2000
const DAY = 'YYYY-MM-DD'

const fail = (e: unknown) => notify.error(e instanceof ApiError ? e.display : String(e))

/** The last full month: the usual period of a review, as its first and last day. */
function lastMonth(): { from: string; to: string } {
  const start = dayjs().subtract(1, 'month').startOf('month')
  return { from: start.format(DAY), to: start.endOf('month').format(DAY) }
}

/** A range of days as the half-open period [first day 00:00, day after the last 00:00). */
function periodOf(days: { from: string; to: string }): { from: string; to: string } {
  return {
    from: dayjs(days.from).startOf('day').toISOString(),
    to: dayjs(days.to).add(1, 'day').startOf('day').toISOString(),
  }
}

/** A titled part of the page. */
function Part({ title, children, testId }: { title: ReactNode; children: ReactNode; testId?: string }) {
  return (
    <Card data-testid={testId}>
      <CardHeader>
        <CardTitle>
          <h2 className="text-base font-semibold">{title}</h2>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  )
}

/**
 * The periodic access review (docs/design/10-security.md section 13.3, decision D28 item 9): issue the access report
 * as of the end of the period (REPORT_ISSUE, kept in the report archive), look at the period's security changes from
 * the audit trail and the segregation-of-duties conflicts, and sign with a comment (ACCESS_REVIEW_SIGN_OFF, which
 * needs a recent second factor: the client asks for it). The server checks every permission again.
 */
export default function AccessReviewPage() {
  const { t } = useTranslation()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const fromId = useId()
  const toId = useId()
  const commentId = useId()
  const [days, setDays] = useState(lastMonth)
  const [runId, setRunId] = useState<string | null>(null)
  const [signOpen, setSignOpen] = useState(false)
  const [signing, setSigning] = useState(false)
  const [comment, setComment] = useState('')
  const [commentError, setCommentError] = useState<string | null>(null)
  const period = periodOf(days)
  const ended = !dayjs(period.to).isAfter(dayjs())

  const changes = useQuery({
    queryKey: ['access-review', 'changes', period.from, period.to],
    queryFn: async () =>
      unwrap(api.GET('/api/security/access-reviews/changes', { params: { query: { from: period.from, to: period.to } } })),
  })
  const conflicts = useQuery({
    queryKey: ['access-review', 'conflicts'],
    queryFn: async () => unwrap(api.GET('/api/security/access-reviews/conflicts')),
  })
  const reviews = useQuery({
    queryKey: ['access-review', 'reviews'],
    queryFn: async () => unwrap(api.GET('/api/security/access-reviews')),
  })
  const changePage = useLocalPage(changes.data?.items, 20)
  const reviewPage = useLocalPage(reviews.data, 10)

  // Another period needs its own report.
  const choose = (next: { from: string; to: string }) => {
    setDays(next)
    setRunId(null)
  }

  const issue = async () => {
    try {
      const output = await runProcess<{ runId: string }>('REPORT_ISSUE', {
        templateId: ACCESS_TEMPLATE,
        params: { asOf: period.to },
      })
      setRunId(output.runId)
      notify.success(t('accessReview.issued'))
    } catch (e) {
      fail(e)
      throw e
    }
  }

  const sign = async () => {
    if (!runId) return
    if (!comment.trim()) {
      setCommentError(t('accessReview.commentRequired'))
      return
    }
    setSigning(true)
    try {
      await runProcess(SIGN_OFF, {
        periodFrom: period.from,
        periodTo: period.to,
        reportRunId: runId,
        reviewComment: comment,
      })
      notify.success(t('accessReview.signed'))
      setComment('')
      setRunId(null)
      setSignOpen(false)
      await queryClient.invalidateQueries({ queryKey: ['access-review', 'reviews'] })
    } catch (e) {
      fail(e)
    } finally {
      setSigning(false)
    }
  }

  const changeColumns = useMemo<ColumnDef<Change, unknown>[]>(
    () => [
      {
        id: 'recorded',
        header: t('audit.recorded'),
        cell: ({ row }) => (row.original.recordedTime ? formatDateTime(row.original.recordedTime) : ''),
      },
      { id: 'actor', header: t('audit.actor'), cell: ({ row }) => <UserName id={row.original.actorId} /> },
      { id: 'entityType', header: t('audit.entityType'), cell: ({ row }) => row.original.entityType },
      { id: 'entityId', header: t('audit.entityId'), cell: ({ row }) => row.original.entityId },
      {
        id: 'action',
        header: t('audit.action'),
        cell: ({ row }) => <Badge variant="secondary">{row.original.action}</Badge>,
      },
      {
        id: 'changed',
        header: t('audit.changed'),
        cell: ({ row }) => Object.keys(row.original.changes ?? {}).join(', '),
      },
    ],
    [t],
  )
  const conflictColumns = useMemo<ColumnDef<Conflict, unknown>[]>(
    () => [
      { id: 'user', header: t('accessReview.user'), cell: ({ row }) => row.original.userName },
      { id: 'rule', header: t('accessReview.rule'), cell: ({ row }) => row.original.ruleCode },
      { id: 'roles', header: t('accessReview.roles'), cell: ({ row }) => (row.original.roles ?? []).join(', ') },
    ],
    [t],
  )
  const reviewColumns = useMemo<ColumnDef<Review, unknown>[]>(
    () => [
      {
        id: 'period',
        header: t('accessReview.period'),
        cell: ({ row: { original: review } }) =>
          `${review.periodFrom ? formatDateTime(review.periodFrom) : ''} – ${
            review.periodTo ? formatDateTime(review.periodTo) : ''}`,
      },
      { id: 'reviewer', header: t('accessReview.reviewer'), cell: ({ row }) => row.original.reviewer },
      {
        id: 'signedAt',
        header: t('accessReview.signedAt'),
        cell: ({ row }) => (row.original.signedAt ? formatDateTime(row.original.signedAt) : ''),
      },
      {
        id: 'changes',
        header: t('accessReview.changeCount'),
        meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => row.original.changesCount,
      },
      {
        id: 'conflicts',
        header: t('accessReview.conflictCount'),
        meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => row.original.conflicts?.length ?? 0,
      },
      {
        id: 'comment',
        header: t('accessReview.comment'),
        meta: { className: 'max-w-xs' },
        cell: ({ row }) => (
          <span className="block truncate" title={row.original.comment ?? undefined}>
            {row.original.comment}
          </span>
        ),
      },
    ],
    [t],
  )

  return (
    <AdminPage title={t('accessReview.title')}>
      <Part title={t('accessReview.period')}>
        <div className="flex flex-wrap items-end gap-3" data-testid="access-review-period">
          <div className="flex flex-col gap-2">
            <Label htmlFor={fromId}>{t('accessReview.periodFrom')}</Label>
            <DatePicker
              id={fromId}
              value={days.from}
              onChange={(from) => {
                // A first day after the last moves the last day with it.
                if (from) choose({ from, to: from > days.to ? from : days.to })
              }}
              className="w-44"
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={toId}>{t('accessReview.periodTo')}</Label>
            <DatePicker
              id={toId}
              value={days.to}
              onChange={(to) => {
                if (to) choose({ from: to < days.from ? to : days.from, to })
              }}
              disabledDays={{ before: dayjs(days.from).toDate() }}
              className="w-44"
            />
          </div>
          {can('report.issue') && (
            <ConfirmDialog
              title={t('accessReview.issueConfirm', { time: formatDateTime(period.to) })}
              description={t('accessReview.issueConfirmHint')}
              confirmLabel={t('reports.issue')}
              onConfirm={issue}
              trigger={
                <Button variant="outline" disabled={!ended} data-testid="access-review-issue">
                  {t('accessReview.issue')}
                </Button>
              }
            />
          )}
          {runId && (
            <Button variant="link" asChild className="px-1">
              <Link to={paths.reportArchive(ACCESS_TEMPLATE)} data-testid="access-review-report">
                {t('accessReview.openReport')}
              </Link>
            </Button>
          )}
        </div>
        {!ended && (
          <Alert>
            <Info aria-hidden />
            <AlertDescription>{t('accessReview.notEnded')}</AlertDescription>
          </Alert>
        )}
      </Part>

      <Part title={t('accessReview.changes', { count: changes.data?.items?.length ?? 0 })}>
        <div data-testid="access-review-changes">
          <DataTable
            label={t('accessReview.changes', { count: changes.data?.items?.length ?? 0 })}
            columns={changeColumns}
            getRowId={(change) => String(change.recordNo)}
            loading={changes.isLoading}
            {...changePage.paging}
            data={changePage.data}
          />
        </div>
      </Part>

      <Part title={t('accessReview.conflicts', { count: conflicts.data?.items?.length ?? 0 })}>
        <div data-testid="access-review-conflicts">
          <DataTable
            label={t('accessReview.conflicts', { count: conflicts.data?.items?.length ?? 0 })}
            columns={conflictColumns}
            getRowId={(row) => `${row.userId}/${row.ruleCode}`}
            loading={conflicts.isLoading}
            data={conflicts.data?.items ?? []}
          />
        </div>
      </Part>

      {can('security.access-review.sign') && (
        <Part title={t('accessReview.sign')}>
          {!runId && <p className="text-muted-foreground text-sm">{t('accessReview.issueFirst')}</p>}
          <div>
            <Button disabled={!runId || !ended} onClick={() => setSignOpen(true)} data-testid="access-review-sign">
              {t('accessReview.sign')}
            </Button>
          </div>
        </Part>
      )}

      <Part title={t('accessReview.signedReviews')}>
        <div data-testid="access-review-list">
          <DataTable
            label={t('accessReview.signedReviews')}
            columns={reviewColumns}
            getRowId={(review) => review.reviewId ?? ''}
            loading={reviews.isLoading}
            {...reviewPage.paging}
            data={reviewPage.data}
          />
        </div>
      </Part>

      <Dialog open={signOpen} onOpenChange={(open) => !open && !signing && setSignOpen(false)}>
        <DialogContent className="sm:max-w-lg">
          <form
            noValidate
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              void sign()
            }}
          >
            <DialogHeader>
              <DialogTitle>{t('accessReview.sign')}</DialogTitle>
              <DialogDescription>
                {`${formatDateTime(period.from)} – ${formatDateTime(period.to)}`}
              </DialogDescription>
            </DialogHeader>
            <div className="flex flex-col gap-2">
              <Label htmlFor={commentId}>{t('accessReview.comment')}</Label>
              <Textarea
                id={commentId}
                value={comment}
                rows={5}
                maxLength={MAX_COMMENT}
                required
                aria-invalid={commentError ? true : undefined}
                aria-describedby={`${commentId}-count${commentError ? ` ${commentId}-error` : ''}`}
                onChange={(event) => {
                  setComment(event.target.value)
                  if (event.target.value.trim()) setCommentError(null)
                }}
                data-testid="access-review-comment"
              />
              <div className="flex justify-between gap-2 text-sm">
                <span id={`${commentId}-error`} className="text-destructive">
                  {commentError}
                </span>
                <span id={`${commentId}-count`} className="text-muted-foreground tabular-nums">
                  {`${comment.length} / ${MAX_COMMENT}`}
                </span>
              </div>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setSignOpen(false)} disabled={signing}>
                {t('confirm.cancel', { ns: UI_NAMESPACE })}
              </Button>
              <Button type="submit" disabled={signing} data-testid="access-review-sign-submit">
                {signing && <Spinner className="text-current" />}
                {t('accessReview.signAction')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </AdminPage>
  )
}
