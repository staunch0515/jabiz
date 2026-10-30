import { useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  Badge,
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
  RadioGroup,
  RadioGroupItem,
  Textarea,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { StatusFilter } from './finance-components'
import { LoadFailed, PublisherLink } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { CASH_RESOLUTIONS, readCashRewards, resolveCashReward, type CashResolution, type CashReward }
  from './reward-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { dueAt: 'dueAt', amount: 'amount', status: 'status' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const KEY = ['qb-ext', 'cash-rewards'] as const

/** An administrator decides a disputed cash reward (`QB_CASH_REWARD_RESOLVE`): paid, unpaid or void, with a note. */
function ResolveDialog({ reward, onClose }: { reward: CashReward; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [resolution, setResolution] = useState<CashResolution>('PAID')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setProblem(null)
    try {
      await resolveCashReward(reward.cashRewardId, resolution, note)
      notify.success(t('reward.cash.resolved', { code: reward.code }))
      await queryClient.invalidateQueries({ queryKey: KEY })
      onClose()
    } catch (error) {
      setProblem(error instanceof Error ? error.message : String(error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogContent data-testid="cash-resolve-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>{t('reward.cash.resolveTitle', { code: reward.code })}</DialogTitle>
            <DialogDescription>
              {t('reward.cash.resolveText', { amount: money(reward.amount), sponsor: reward.sponsorCompany ?? '?',
                taker: reward.takerName ?? '?', note: reward.disputeNote ?? '' })}
            </DialogDescription>
          </DialogHeader>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('reward.cash.resolution')}</legend>
            <RadioGroup value={resolution} onValueChange={(value) => setResolution(value as CashResolution)}
              data-testid="cash-resolution">
              {CASH_RESOLUTIONS.map((value) => (
                <div key={value} className="flex items-center gap-2">
                  <RadioGroupItem id={`${id}-${value}`} value={value} data-testid={`cash-resolution-${value}`} />
                  <Label htmlFor={`${id}-${value}`} className="font-normal">{t(`reward.cash.resolutions.${value}`)}</Label>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('reward.cash.note')}</Label>
            <Textarea id={`${id}-note`} value={note} maxLength={500} aria-describedby={`${id}-note-hint`}
              data-testid="cash-resolve-note" onChange={(event) => setNote(event.target.value)} />
            <p id={`${id}-note-hint`} className="text-muted-foreground text-sm">{t('reward.cash.noteHint')}</p>
          </div>
          {problem && <Alert variant="destructive"><AlertDescription>{problem}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('manual.dialog.cancel')}</Button>
            <Button type="submit" disabled={busy} aria-busy={busy || undefined} data-testid="cash-resolve-send">
              {t('reward.cash.send')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Every cash reward (docs/quizbuks/plans/Q22-reward-methods.md §2), newest first, by state and text: who owes whom
 * how much and where it stands; a disputed one is decided here by whoever may.
 */
export default function CashRewardsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const status = useLabels(DICTIONARY.cashStatus)
  const [search, setSearch] = useSearchParams()
  const filterStatus = search.get('status')
  const [text, setText] = useState(search.get('q') ?? '')
  const q = search.get('q') ?? ''
  const [resolving, setResolving] = useState<CashReward | null>(null)
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const { query, table, sortable } = usePaged([...KEY, filterStatus, q],
    (offset, limit, sorts) => readCashRewards(filterStatus, q, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<CashReward, unknown>[]>(() => [
    { id: 'dueAt', header: t('reward.cash.dueAt'), cell: ({ row }) => when(row.original.dueAt) },
    { id: 'code', header: t('reward.cash.code'),
      cell: ({ row }) => <code className="font-mono" data-testid="cash-row-code">{row.original.code}</code> },
    { id: 'sponsor', header: t('reward.cash.sponsor'),
      cell: ({ row }) => (
        <PublisherLink kind={row.original.publisherKind} id={row.original.publisherId}
          label={row.original.sponsorCompany} />
      ) },
    { id: 'taker', header: t('reward.cash.taker'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.userId)} className={linkClass}>
          {row.original.takerName ?? <UserName id={row.original.userId} />}
        </Link>
      ) },
    { id: 'publication', header: t('reward.cash.publication'), cell: ({ row }) => row.original.publicationName },
    { id: 'amount', header: t('reward.cash.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'status', header: t('reward.cash.status'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <Badge variant={row.original.status === 'DISPUTED' ? 'destructive' : 'outline'} className="w-fit"
            data-testid="cash-row-status" data-status={row.original.status}>
            {status(row.original.status)}
          </Badge>
          {row.original.status === 'DUE' && row.original.payBy && (
            <span className="text-muted-foreground text-xs">{t('reward.cash.payBy', { time: when(row.original.payBy) })}</span>
          )}
          {row.original.remindedAt && (
            <span className="text-muted-foreground text-xs">{t('reward.cash.reminded', { time: when(row.original.remindedAt) })}</span>
          )}
          {row.original.markedPaidAt && (
            <span className="text-muted-foreground text-xs">{t('reward.cash.markedPaid', { time: when(row.original.markedPaidAt) })}</span>
          )}
          {row.original.markedPaidNote && (
            <span className="text-xs">{t('reward.cash.sponsorNote', { note: row.original.markedPaidNote })}</span>
          )}
          {row.original.disputeNote && (
            <span className="text-xs" data-testid="cash-row-dispute">{t('reward.cash.disputeNote', { note: row.original.disputeNote })}</span>
          )}
          {row.original.resolveNote && (
            <span className="text-muted-foreground text-xs">{t('reward.cash.resolveNote', { note: row.original.resolveNote })}</span>
          )}
        </span>
      ) },
    { id: 'actions', header: () => <span className="sr-only">{t('reward.cash.resolve')}</span>,
      cell: ({ row }) => row.original.status === 'DISPUTED' && can(PERMISSION.cashResolve) ? (
        <span className="flex justify-end">
          <Button size="sm" onClick={() => setResolving(row.original)} data-testid="cash-row-resolve">
            {t('reward.cash.resolve')}
            <span className="sr-only">: {row.original.code}</span>
          </Button>
        </span>
      ) : null },
  ], [t, status, can])

  return (
    <div className="flex flex-col gap-4" data-testid="cash-rewards">
      <PageHeader title={t('reward.cash.title')} description={t('reward.cash.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.cashStatus} value={filterStatus} testId="cash-status"
          onChange={(value) => set({ status: value })} />
        <form role="search" className="flex items-end gap-2" onSubmit={(event) => {
          event.preventDefault()
          set({ q: text.trim() || null })
        }}>
          <div className="flex flex-col gap-1">
            <Label htmlFor="cash-q" className="sr-only">{t('reward.cash.search')}</Label>
            <Input id="cash-q" type="search" value={text} maxLength={100} placeholder={t('reward.cash.search')}
              data-testid="cash-search" onChange={(event) => setText(event.target.value)} />
          </div>
          <Button type="submit" variant="outline">{t('reward.cash.search')}</Button>
        </form>
        {(filterStatus || q) && (
          <Button variant="ghost" size="sm" onClick={() => {
            setText('')
            set({ status: null, q: null })
          }}>{t('reward.cash.all')}</Button>
        )}
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('reward.cash.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.cashRewardId}
            empty={t('reward.cash.none')}
            rowProps={(row) => ({ 'data-testid': 'cash-row', 'data-id': row.cashRewardId, 'data-status': row.status })} />
        )}
      {resolving && <ResolveDialog reward={resolving} onClose={() => setResolving(null)} />}
    </div>
  )
}
