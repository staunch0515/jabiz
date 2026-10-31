import { useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  Button,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageHeader,
  Textarea,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { StatusFilter } from './finance-components'
import { EmptyState, LoadFailed, PublisherLink, StatusBadge } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { readRedemptions, voidRedemption, type Redemption } from './reward-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { dueAt: 'dueAt', amount: 'amount', status: 'status' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const KEY = ['qb-ext', 'redemptions'] as const

/** An administrator voids a prize not yet collected (`QB_REDEMPTION_VOID`), with a note for the record. */
function VoidDialog({ prize, onClose }: { prize: Redemption; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setProblem(null)
    try {
      await voidRedemption(prize.redemptionId, note)
      notify.success(t('reward.redeem.voided', { code: prize.code }))
      await queryClient.invalidateQueries({ queryKey: KEY })
      onClose()
    } catch (error) {
      setProblem(error instanceof Error ? error.message : String(error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="redeem-void-dialog">
        <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('reward.redeem.voidTitle', { code: prize.code })}</DialogTitle>
            <DialogDescription>
              {t('reward.redeem.voidText', { sponsor: prize.sponsorCompany ?? '?', taker: prize.takerName ?? '?',
                prize: prize.description })}
            </DialogDescription>
          </DialogHeader>
          {problem && <Alert variant="destructive"><AlertDescription>{problem}</AlertDescription></Alert>}
          <div className="flex flex-col gap-1">
            <Label htmlFor="redeem-void-note">{t('reward.redeem.note')}</Label>
            <Textarea id="redeem-void-note" value={note} rows={3} maxLength={500} data-testid="redeem-void-note"
              onChange={(event) => setNote(event.target.value)} />
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('reward.redeem.cancel')}</Button>
            <Button type="submit" variant="destructive" disabled={busy} aria-busy={busy || undefined}
              data-testid="redeem-void-send">
              {t('reward.redeem.void')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Every prize to collect at a sponsor's place (docs/quizbuks/plans/Q22-reward-methods.md, Q22b), newest first, by
 * state and text: who won what where and whether it was collected; one not collected yet is voided here by whoever
 * may.
 */
export default function RedemptionsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const status = useLabels(DICTIONARY.redemptionStatus)
  const [search, setSearch] = useSearchParams()
  const filterStatus = search.get('status')
  const [text, setText] = useState(search.get('q') ?? '')
  const q = search.get('q') ?? ''
  const [voiding, setVoiding] = useState<Redemption | null>(null)
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const more = useMoreFilters([
    { kind: 'amount', name: 'amount', label: t('reward.redeem.value') },
    { kind: 'time', name: 'dueAt', label: t('reward.redeem.dueAt') },
  ], 'redeem-filters')
  const { query, table, sortable } = usePaged([...KEY, filterStatus, q, more.filters],
    (offset, limit, sorts) => readRedemptions(filterStatus, q, offset, limit, sorts, more.filters), true, SORTS)
  const columns = useMemo<ColumnDef<Redemption, unknown>[]>(() => [
    { id: 'dueAt', header: t('reward.redeem.dueAt'), cell: ({ row }) => when(row.original.dueAt) },
    { id: 'code', header: t('reward.redeem.code'),
      cell: ({ row }) => <code className="font-mono" data-testid="redeem-row-code">{row.original.code}</code> },
    { id: 'sponsor', header: t('reward.redeem.sponsor'),
      cell: ({ row }) => (
        <PublisherLink kind={row.original.publisherKind} id={row.original.publisherId}
          label={row.original.sponsorCompany} />
      ) },
    { id: 'taker', header: t('reward.redeem.taker'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.userId)} className={linkClass}>
          {row.original.takerName ?? <UserName id={row.original.userId} />}
        </Link>
      ) },
    { id: 'prize', header: t('reward.redeem.prize'),
      cell: ({ row }) => (
        <span className="flex flex-col">
          <span className="whitespace-pre-line">{row.original.description}</span>
          <span className="text-muted-foreground text-xs">{row.original.publicationName}</span>
        </span>
      ) },
    { id: 'amount', header: t('reward.redeem.value'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'status', header: t('reward.redeem.status'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <StatusBadge code={row.original.status} label={status(row.original.status)} className="w-fit"
            testId="redeem-row-status" />
          {row.original.status === 'DUE' && row.original.expiresAt && (
            <span className="text-muted-foreground text-xs">{t('reward.redeem.expiresAt', { time: when(row.original.expiresAt) })}</span>
          )}
          {row.original.status === 'CLAIMED' && row.original.confirmBy && (
            <span className="text-muted-foreground text-xs">{t('reward.redeem.confirmBy', { time: when(row.original.confirmBy) })}</span>
          )}
          {row.original.redeemedAt && (
            <span className="text-muted-foreground text-xs">
              {t('reward.redeem.redeemedAt', { time: when(row.original.redeemedAt) })}
              {row.original.claimKind ? ` · ${t(`reward.redeem.kind.${row.original.claimKind}`)}` : ''}
            </span>
          )}
          {row.original.voidNote && (
            <span className="text-xs" data-testid="redeem-row-void-note">{t('reward.redeem.voidNote', { note: row.original.voidNote })}</span>
          )}
        </span>
      ) },
    { id: 'actions', header: () => <span className="sr-only">{t('reward.redeem.void')}</span>,
      cell: ({ row }) => (row.original.status === 'DUE' || row.original.status === 'CLAIMED')
        && can(PERMISSION.redemptionVoid) ? (
          <span className="flex justify-end">
            <Button size="sm" variant="outline" onClick={() => setVoiding(row.original)} data-testid="redeem-row-void">
              {t('reward.redeem.void')}
              <span className="sr-only">: {row.original.code}</span>
            </Button>
          </span>
        ) : null },
  ], [t, status, can])

  return (
    <div className="flex flex-col gap-4" data-testid="redemptions">
      <PageHeader title={t('reward.redeem.title')} description={t('reward.redeem.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.redemptionStatus} value={filterStatus} testId="redeem-status"
          onChange={(value) => set({ status: value })} />
        <form role="search" className="flex items-end gap-2" onSubmit={(event) => {
          event.preventDefault()
          set({ q: text.trim() || null })
        }}>
          <div className="flex flex-col gap-1">
            <Label htmlFor="redeem-q" className="sr-only">{t('reward.redeem.search')}</Label>
            <Input id="redeem-q" type="search" value={text} maxLength={100} placeholder={t('reward.redeem.search')}
              data-testid="redeem-search" onChange={(event) => setText(event.target.value)} />
          </div>
          <Button type="submit" variant="outline">{t('reward.redeem.search')}</Button>
        </form>
        {(filterStatus || q) && (
          <Button variant="ghost" size="sm" onClick={() => {
            setText('')
            set({ status: null, q: null })
          }}>{t('reward.redeem.all')}</Button>
        )}
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('reward.redeem.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.redemptionId}
            empty={<EmptyState art="money" title={t('reward.redeem.none')} />}
            rowProps={(row) => ({ 'data-testid': 'redeem-row', 'data-id': row.redemptionId, 'data-status': row.status })} />
        )}
      {voiding && <VoidDialog prize={voiding} onClose={() => setVoiding(null)} />}
    </div>
  )
}
