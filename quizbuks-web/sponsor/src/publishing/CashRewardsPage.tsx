import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
  PageHeader,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  type ColumnDef,
  type PaginationState,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { HandCoins, Search } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { CASH_STATUSES, markCashPaid, searchCashRewards, type CashRewardRow, type CashStatus } from './api'
import { publicationPath } from './paths'
import { useCashStatusLabel } from './shared'

export const CASH_REWARDS_PATH = '/cash-rewards'
export const CASH_REWARDS_QUERY = ['qb', 'cash-rewards'] as const
const PAGE_SIZE = 20

const STATUS_BADGES: Record<CashStatus, 'warning' | 'secondary' | 'success' | 'destructive' | 'outline'> = {
  DUE: 'warning',
  MARKED_PAID: 'secondary',
  CONFIRMED: 'success',
  DISPUTED: 'destructive',
  OVERDUE: 'secondary',
  VOID: 'outline',
}

/** The sponsor records that they handed the taker the cash (`QB_CASH_REWARD_MARK_PAID`), with a note for the record. */
function MarkPaidDialog({ reward, onClose }: { reward: CashRewardRow; onClose: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [note, setNote] = useState('')
  const mark = useMutation({
    mutationFn: () => markCashPaid(reward.cashRewardId, note),
    onSuccess: async () => {
      notify.success(t('publishing.cash.marked', { code: reward.code }))
      await queryClient.invalidateQueries({ queryKey: CASH_REWARDS_QUERY })
      onClose()
    },
  })
  return (
    <Dialog open onOpenChange={(open) => {
      if (!open && !mark.isPending) onClose()
    }}>
      <DialogContent data-testid="cash-mark-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event: FormEvent) => {
          event.preventDefault()
          if (!mark.isPending) mark.mutate()
        }}>
          <DialogHeader>
            <DialogTitle>{t('publishing.cash.markTitle', { code: reward.code })}</DialogTitle>
            <DialogDescription>
              {t('publishing.cash.markText', { amount: formatKudos(reward.amount), taker: reward.takerName ?? '?' })}
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('publishing.cash.note')}</Label>
            <Input id={`${id}-note`} value={note} maxLength={200} autoComplete="off" data-testid="cash-mark-note"
              aria-describedby={`${id}-note-hint`} onChange={(event) => setNote(event.target.value)} />
            <p id={`${id}-note-hint`} className="text-muted-foreground text-sm">{t('publishing.cash.noteHint')}</p>
          </div>
          {mark.isError && <p className="text-destructive text-sm" role="alert">{problemText(mark.error)}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={mark.isPending} onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="submit" disabled={mark.isPending} aria-busy={mark.isPending || undefined}
              data-testid="cash-mark-confirm">
              <HandCoins aria-hidden />
              {t('publishing.cash.mark')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The cash rewards the sponsor owes or paid (Q22, template `qb.sponsor.cash-rewards`): who, how much, the code the
 * taker shows at the counter, and where each stands. The sponsor marks one paid once they handed the cash over; the
 * taker then confirms it in the app. A disputed one waits for an administrator.
 */
export function CashRewardsPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const statusLabel = useCashStatusLabel()
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState<CashStatus | null>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [marking, setMarking] = useState<CashRewardRow | null>(null)
  const rewards = useQuery({
    queryKey: [...CASH_REWARDS_QUERY, q, status, pagination],
    queryFn: () => searchCashRewards({ q, status, offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize }),
    placeholderData: keepPreviousData,
  })
  const columns = useMemo<ColumnDef<CashRewardRow, unknown>[]>(() => [
    { id: 'code', header: t('publishing.cash.code'), enableSorting: false,
      cell: ({ row }) => <span className="font-mono" data-testid="cash-code">{row.original.code}</span> },
    { id: 'taker', header: t('publishing.cash.taker'), enableSorting: false,
      cell: ({ row }) => row.original.takerName ?? <span className="text-muted-foreground">?</span> },
    { id: 'publication', header: t('publishing.cash.publication'), enableSorting: false,
      cell: ({ row }) => (
        <Link to={publicationPath(row.original.publicationId)} className="underline-offset-4 hover:underline">
          {row.original.publicationName}
        </Link>
      ) },
    { id: 'amount', header: t('publishing.cash.amount'), enableSorting: false, meta: { className: 'text-right tabular-nums' },
      cell: ({ row }) => formatKudos(row.original.amount) },
    { id: 'status', header: t('publishing.cash.status'), enableSorting: false,
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <Badge variant={STATUS_BADGES[row.original.status]} className="w-fit" data-testid="cash-status"
            data-status={row.original.status}>
            {statusLabel(row.original.status)}
          </Badge>
          {row.original.status === 'DUE' && row.original.payBy && (
            <span className="text-muted-foreground text-xs">{t('publishing.cash.payBy', { time: formatDateTime(row.original.payBy) })}</span>
          )}
          {row.original.status === 'MARKED_PAID' && row.original.confirmBy && (
            <span className="text-muted-foreground text-xs">{t('publishing.cash.confirmBy', { time: formatDateTime(row.original.confirmBy) })}</span>
          )}
          {row.original.status === 'DISPUTED' && row.original.disputeNote && (
            <span className="text-xs" data-testid="cash-dispute-note">{t('publishing.cash.disputeNote', { note: row.original.disputeNote })}</span>
          )}
          {row.original.resolveNote && (
            <span className="text-muted-foreground text-xs">{t('publishing.cash.resolveNote', { note: row.original.resolveNote })}</span>
          )}
        </span>
      ) },
    { id: 'dueAt', header: t('publishing.cash.dueAt'), enableSorting: false,
      cell: ({ row }) => formatDateTime(row.original.dueAt) },
    { id: 'actions', header: '', enableSorting: false,
      cell: ({ row }) => row.original.status === 'DUE' ? (
        <Button size="sm" variant="outline" data-testid="cash-mark" onClick={() => setMarking(row.original)}>
          <HandCoins aria-hidden />
          {t('publishing.cash.mark')}
        </Button>
      ) : null },
  ], [t, statusLabel])

  const search = (event: FormEvent) => {
    event.preventDefault()
    setQ(text.trim())
    setPagination((p) => ({ ...p, pageIndex: 0 }))
  }

  return (
    <section className="flex flex-col gap-4" data-testid="cash-rewards">
      <PageHeader title={t('publishing.cash.title')} description={t('publishing.cash.intro')} />
      <div className="flex flex-wrap items-end gap-2">
        <form role="search" onSubmit={search} className="flex gap-2">
          <Label htmlFor={`${id}-q`} className="sr-only">{t('publishing.cash.search')}</Label>
          <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('publishing.cash.search')}
            data-testid="cash-search" onChange={(event) => setText(event.target.value)} />
          <Button type="submit" variant="outline" size="icon" aria-label={t('publishing.cash.search')}>
            <Search aria-hidden />
          </Button>
        </form>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-status`} className="sr-only">{t('publishing.cash.status')}</Label>
          <Select value={status ?? 'ALL'} onValueChange={(value) => {
            setStatus(value === 'ALL' ? null : (value as CashStatus))
            setPagination((p) => ({ ...p, pageIndex: 0 }))
          }}>
            <SelectTrigger id={`${id}-status`} className="w-56" data-testid="cash-status-filter">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">{t('publishing.cash.allStatuses')}</SelectItem>
              {CASH_STATUSES.map((code) => <SelectItem key={code} value={code}>{statusLabel(code)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </div>
      {rewards.isError ? (
        <PageState kind="error" description={problemText(rewards.error)}
          action={<Button variant="outline" onClick={() => void rewards.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <DataTable label={t('publishing.cash.title')} columns={columns} data={rewards.data?.items ?? []}
          getRowId={(row) => row.cashRewardId} loading={rewards.isPending} stale={rewards.isPlaceholderData}
          empty={t('publishing.cash.none')} pagination={pagination} onPaginationChange={setPagination}
          rowCount={rewards.data?.total}
          rowProps={(row) => ({ 'data-testid': 'cash-row', 'data-status': row.status })} />
      )}
      {marking && <MarkPaidDialog reward={marking} onClose={() => setMarking(null)} />}
    </section>
  )
}
