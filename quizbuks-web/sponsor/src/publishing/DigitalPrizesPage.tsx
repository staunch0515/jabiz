import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  DataTable,
  Input,
  Label,
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
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { DIGITAL_STATUSES, searchDigitalPrizes, type DigitalPrizeRow, type DigitalStatus } from './api'
import { publicationPath } from './paths'
import { useDigitalDeliveryLabel, useDigitalStatusLabel } from './shared'

export const DIGITAL_PRIZES_PATH = '/digital-prizes'
export const DIGITAL_PRIZES_QUERY = ['qb', 'digital-prizes'] as const
const PAGE_SIZE = 20

const STATUS_BADGES: Record<DigitalStatus, 'warning' | 'secondary' | 'success' | 'destructive' | 'outline'> = {
  DUE: 'warning',
  DELIVERED: 'secondary',
  CONFIRMED: 'success',
  DISPUTED: 'destructive',
  OVERDUE: 'secondary',
  VOID: 'outline',
}

/**
 * The digital prizes takers won from the sponsor (Q33, template `qb.sponsor.digital-prizes`): who, from which
 * publication, what the prize is (its code's last characters only; the code itself is sealed), its value, how it is
 * delivered and where it stands. A prize the platform handed out is delivered at once; one the sponsor sends by hand
 * is due until they send it (the sending itself comes with Q33b). A disputed one waits for an administrator.
 */
export function DigitalPrizesPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const statusLabel = useDigitalStatusLabel()
  const deliveryLabel = useDigitalDeliveryLabel()
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState<DigitalStatus | null>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const prizes = useQuery({
    queryKey: [...DIGITAL_PRIZES_QUERY, q, status, pagination],
    queryFn: () => searchDigitalPrizes({ q, status, offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize }),
    placeholderData: keepPreviousData,
  })
  const columns = useMemo<ColumnDef<DigitalPrizeRow, unknown>[]>(() => [
    { id: 'taker', header: t('publishing.digital.taker'), enableSorting: false,
      cell: ({ row }) => row.original.takerName ?? <span className="text-muted-foreground">?</span> },
    { id: 'publication', header: t('publishing.digital.publication'), enableSorting: false,
      cell: ({ row }) => (
        <Link to={publicationPath(row.original.publicationId)} className="underline-offset-4 hover:underline">
          {row.original.publicationName}
        </Link>
      ) },
    { id: 'prize', header: t('publishing.digital.prize'), enableSorting: false,
      cell: ({ row }) => (
        <span className="flex flex-col gap-0.5">
          <span className="whitespace-pre-line" data-testid="digital-description">{row.original.description ?? ''}</span>
          {row.original.hint && <span className="text-muted-foreground font-mono text-xs" data-testid="digital-hint">{row.original.hint}</span>}
        </span>
      ) },
    { id: 'amount', header: t('publishing.digital.value'), enableSorting: false, meta: { className: 'text-right tabular-nums' },
      cell: ({ row }) => formatKudos(row.original.amount) },
    { id: 'delivery', header: t('publishing.digital.delivery'), enableSorting: false,
      cell: ({ row }) => (row.original.delivery
        ? <span data-testid="digital-delivery">{deliveryLabel(row.original.delivery)}</span> : '') },
    { id: 'status', header: t('publishing.digital.status'), enableSorting: false,
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <Badge variant={STATUS_BADGES[row.original.status]} className="w-fit" data-testid="digital-status"
            data-status={row.original.status}>
            {statusLabel(row.original.status)}
          </Badge>
          {row.original.status === 'DUE' && row.original.sendBy && (
            <span className="text-muted-foreground text-xs">{t('publishing.digital.sendBy', { time: formatDateTime(row.original.sendBy) })}</span>
          )}
          {row.original.status === 'DELIVERED' && row.original.confirmBy && (
            <span className="text-muted-foreground text-xs">{t('publishing.digital.confirmBy', { time: formatDateTime(row.original.confirmBy) })}</span>
          )}
          {row.original.status === 'DELIVERED' && row.original.openedAt && (
            <span className="text-muted-foreground text-xs" data-testid="digital-opened">{t('publishing.digital.openedAt', { time: formatDateTime(row.original.openedAt) })}</span>
          )}
          {row.original.status === 'DISPUTED' && row.original.disputeNote && (
            <span className="text-xs" data-testid="digital-dispute-note">{t('publishing.digital.disputeNote', { note: row.original.disputeNote })}</span>
          )}
          {row.original.resolveNote && (
            <span className="text-muted-foreground text-xs">{t('publishing.digital.resolveNote', { note: row.original.resolveNote })}</span>
          )}
        </span>
      ) },
    { id: 'dueAt', header: t('publishing.digital.dueAt'), enableSorting: false,
      cell: ({ row }) => formatDateTime(row.original.dueAt) },
  ], [t, statusLabel, deliveryLabel])

  const search = (event: FormEvent) => {
    event.preventDefault()
    setQ(text.trim())
    setPagination((p) => ({ ...p, pageIndex: 0 }))
  }

  return (
    <section className="flex flex-col gap-4" data-testid="digital-prizes">
      <PageHeader title={t('publishing.digital.title')} description={t('publishing.digital.intro')} />
      <div className="flex flex-wrap items-end gap-2">
        <form role="search" onSubmit={search} className="flex gap-2">
          <Label htmlFor={`${id}-q`} className="sr-only">{t('publishing.digital.search')}</Label>
          <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('publishing.digital.search')}
            data-testid="digital-search" onChange={(event) => setText(event.target.value)} />
          <Button type="submit" variant="outline" size="icon" aria-label={t('publishing.digital.search')}>
            <Search aria-hidden />
          </Button>
        </form>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-status`} className="sr-only">{t('publishing.digital.status')}</Label>
          <Select value={status ?? 'ALL'} onValueChange={(value) => {
            setStatus(value === 'ALL' ? null : (value as DigitalStatus))
            setPagination((p) => ({ ...p, pageIndex: 0 }))
          }}>
            <SelectTrigger id={`${id}-status`} className="w-56" data-testid="digital-status-filter">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">{t('publishing.digital.allStatuses')}</SelectItem>
              {DIGITAL_STATUSES.map((code) => <SelectItem key={code} value={code}>{statusLabel(code)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </div>
      {prizes.isError ? (
        <PageState kind="error" description={problemText(prizes.error)}
          action={<Button variant="outline" onClick={() => void prizes.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <DataTable label={t('publishing.digital.title')} columns={columns} data={prizes.data?.items ?? []}
          getRowId={(row) => row.digitalPrizeId} loading={prizes.isPending} stale={prizes.isPlaceholderData}
          empty={t('publishing.digital.none')} pagination={pagination} onPaginationChange={setPagination}
          rowCount={prizes.data?.total}
          rowProps={(row) => ({ 'data-testid': 'digital-row', 'data-status': row.status })} />
      )}
    </section>
  )
}
