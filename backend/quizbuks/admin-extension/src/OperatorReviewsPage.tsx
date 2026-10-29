import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  Checkbox,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Label,
  notify,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import { LoadFailed } from './components'
import { useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH } from './ids'
import { OPERATORS_KEY, readOperatorReviews, readOperators, spotCheck, type CheckResult, type OperatorReview } from './operator-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const KEY = [...OPERATORS_KEY, 'reviews'] as const
/** The select's value for every operator (a select item cannot have an empty value). */
const ALL = 'all'

/** Records a spot check (`QB_REVIEW_SPOT_CHECK`): checked, or a problem with what it is. */
function CheckDialog({ review, result, onClose }: { review: OperatorReview; result: CheckResult; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [note, setNote] = useState(review.checkNote ?? '')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const needsNote = result === 'PROBLEM'
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || (needsNote && !note.trim())) return
    setBusy(true)
    setProblem(null)
    try {
      await spotCheck(review.decisionId, result, note.trim() || null)
      notify.success(t('operator.reviews.checked'))
      void queryClient.invalidateQueries({ queryKey: KEY })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="spot-check-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t(needsNote ? 'operator.reviews.problemTitle' : 'operator.reviews.okTitle')}</DialogTitle>
            <DialogDescription>{review.title ?? review.entityId} · {review.operatorName}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('operator.reviews.note')}</Label>
            <Textarea id={`${id}-note`} value={note} maxLength={2000} required={needsNote}
              aria-describedby={`${id}-note-hint`} data-testid="spot-check-note"
              onChange={(event) => setNote(event.target.value)} />
            <p id={`${id}-note-hint`} className="text-muted-foreground text-sm">
              {t(needsNote ? 'operator.reviews.problemHint' : 'operator.reviews.okHint')}
            </p>
          </div>
          {problem && <Alert variant="destructive" data-testid="spot-check-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('operator.cancel')}</Button>
            <Button type="submit" disabled={busy || (needsNote && !note.trim())} aria-busy={busy || undefined}
              data-testid="spot-check-submit">{t('operator.reviews.record')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Headquarters' spot checks (D-Q48-5): every review an operator's member decided (an operator's review is final),
 * newest first, by operator and whether it was checked. A problem is noted here; headquarters then acts itself
 * (takes the publication down, bans the sponsor), which the operator does not undo.
 */
export default function OperatorReviewsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const check = useLabels(DICTIONARY.spotCheckResult)
  const [search, setSearch] = useSearchParams()
  const operatorId = search.get('operator')
  const unchecked = search.get('unchecked') !== 'false'
  const [checking, setChecking] = useState<{ review: OperatorReview; result: CheckResult } | null>(null)
  const operators = useQuery({ queryKey: [...OPERATORS_KEY, 'choices'], queryFn: () => readOperators(0, 200) })
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const { query, table } = usePaged([...KEY, operatorId, unchecked],
    (offset, limit) => readOperatorReviews(operatorId, unchecked, offset, limit))
  const columns = useMemo<ColumnDef<OperatorReview, unknown>[]>(() => [
    { id: 'decidedAt', header: t('operator.reviews.decidedAt'), cell: ({ row }) => when(row.original.decidedAt) },
    { id: 'operator', header: t('operator.reviews.operator'), cell: ({ row }) => row.original.operatorName },
    {
      id: 'about',
      header: t('operator.reviews.about'),
      cell: ({ row }) => {
        const r = row.original
        const to = r.subject === 'qb.publication' ? PATH.publication(r.entityId)
          : r.sponsorId ? PATH.sponsor(r.sponsorId) : null
        const label = `${t(`operator.reviews.subjects.${r.subject === 'qb.publication' ? 'publication' : 'sponsor'}`)}: ${
          r.title ?? r.entityId}`
        return to ? <Link to={to} className={linkClass} data-testid="review-about">{label}</Link> : label
      },
    },
    {
      id: 'decision',
      header: t('operator.reviews.decision'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <Badge variant={row.original.decision === 'APPROVE' ? 'secondary' : 'outline'} className="w-fit">
            {t(`operator.reviews.decisions.${row.original.decision === 'APPROVE' ? 'APPROVE' : 'REJECT'}`)}
          </Badge>
          {row.original.reason && <span className="text-muted-foreground text-sm">{row.original.reason}</span>}
        </span>
      ),
    },
    { id: 'approver', header: t('operator.reviews.approver'), cell: ({ row }) => <UserName id={row.original.approverId} /> },
    {
      id: 'check',
      header: t('operator.reviews.check'),
      cell: ({ row }) => (row.original.checkResult ? (
        <span className="flex flex-col gap-1" data-testid="review-check">
          <Badge variant={row.original.checkResult === 'PROBLEM' ? 'destructive' : 'secondary'} className="w-fit">
            {check(row.original.checkResult)}
          </Badge>
          {row.original.checkNote && <span className="text-sm">{row.original.checkNote}</span>}
          <span className="text-muted-foreground text-sm">{when(row.original.checkedAt)}</span>
        </span>
      ) : <span className="text-muted-foreground text-sm">{t('operator.reviews.notChecked')}</span>),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('operator.reviews.check')}</span>,
      cell: ({ row }) => (
        <span className="flex justify-end gap-2">
          <Button size="sm" variant="outline" data-testid="review-ok"
            onClick={() => setChecking({ review: row.original, result: 'OK' })}>
            {t('operator.reviews.ok')}<span className="sr-only">: {row.original.title ?? row.original.entityId}</span>
          </Button>
          <Button size="sm" variant="outline" data-testid="review-problem"
            onClick={() => setChecking({ review: row.original, result: 'PROBLEM' })}>
            {t('operator.reviews.problem')}<span className="sr-only">: {row.original.title ?? row.original.entityId}</span>
          </Button>
        </span>
      ),
    },
  ], [t, check])
  return (
    <div className="flex flex-col gap-4" data-testid="operator-reviews">
      <PageHeader title={t('operator.reviews.title')} description={t('operator.reviews.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('operator.reviews.filters')}>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-operator`}>{t('operator.reviews.operator')}</Label>
          <Select value={operatorId ?? ALL} onValueChange={(value) => set({ operator: value === ALL ? null : value })}>
            <SelectTrigger id={`${id}-operator`} className="w-64" data-testid="reviews-operator">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t('operator.reviews.allOperators')}</SelectItem>
              {(operators.data?.items ?? []).map((operator) => (
                <SelectItem key={operator.operatorId} value={operator.operatorId}>{operator.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex items-center gap-2 pb-2">
          <Checkbox id={`${id}-unchecked`} checked={unchecked} data-testid="reviews-unchecked"
            onCheckedChange={(value) => set({ unchecked: value === true ? null : 'false' })} />
          <Label htmlFor={`${id}-unchecked`} className="font-normal">{t('operator.reviews.onlyUnchecked')}</Label>
        </div>
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('operator.reviews.title')} columns={columns} {...table} getRowId={(row) => row.decisionId}
            empty={t('operator.reviews.none')}
            rowProps={(row) => ({ 'data-testid': 'review-row', 'data-id': row.decisionId })} />
        )}
      {checking && <CheckDialog review={checking.review} result={checking.result} onClose={() => setChecking(null)} />}
    </div>
  )
}
