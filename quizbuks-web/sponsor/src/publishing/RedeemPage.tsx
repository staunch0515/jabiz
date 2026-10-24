import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
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
import { Check, Download, FileImage, Gift, Printer, RefreshCw, Search, X } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { download } from '../content/transfer'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { LANGUAGES } from '../qb/messageKeys'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import {
  confirmRedemption,
  declineRedemption,
  issueRedeemCode,
  REDEMPTION_STATUSES,
  searchRedemptions,
  type RedemptionRow,
  type RedemptionStatus,
} from './api'
import { publicationPath, redeemLink } from './paths'
import { posterPng, qrFileName, qrImageUrl, qrPng, qrSvg } from './qr'
import { useRedemptionStatusLabel } from './shared'

export const REDEEM_PATH = '/redeem'
export const REDEMPTIONS_QUERY = ['qb', 'redemptions'] as const
const ROTATING_QUERY = ['qb', 'redeem-code', 'rotating'] as const
const PRINTED_QUERY = ['qb', 'redeem-code', 'printed'] as const
/** The console asks for a new rotating code every minute; the server's lasts two. */
const ROTATE_EVERY = 60_000
/** How often the claims to confirm and the day's redemptions are read while the page is open. */
const POLL_EVERY = 5_000
const PAGE_SIZE = 20

const STATUS_BADGES: Record<RedemptionStatus, 'warning' | 'secondary' | 'success' | 'outline'> = {
  DUE: 'secondary',
  CLAIMED: 'warning',
  REDEEMED: 'success',
  EXPIRED: 'outline',
  VOID: 'outline',
}

/** The start of the device's day, for the list of what was collected today. */
function startOfToday(): string {
  const day = new Date()
  day.setHours(0, 0, 0, 0)
  return day.toISOString()
}

/**
 * The rotating code (Q22b): a fresh one every minute, so that what is on the screen is always the sponsor's own now;
 * a taker who scans it collects their prize at once.
 */
function RotatingCode() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const code = useQuery({ queryKey: ROTATING_QUERY, queryFn: () => issueRedeemCode('ROTATING'),
    refetchInterval: ROTATE_EVERY, refetchIntervalInBackground: false, staleTime: ROTATE_EVERY })
  const link = code.data ? redeemLink(code.data.redeemKey) : null
  const image = useQuery({ queryKey: ['redeem-qr', link], queryFn: () => qrImageUrl(link!), enabled: !!link,
    staleTime: Infinity })
  if (code.isError) {
    return <PageState kind="error" description={problemText(code.error)}
      action={<Button variant="outline" onClick={() => void code.refetch()}>{tQb('retry')}</Button>} />
  }
  return (
    <section className="flex flex-col items-center gap-3 rounded-lg border p-4" aria-labelledby="redeem-rotating-title"
      data-testid="redeem-rotating">
      <h2 id="redeem-rotating-title" className="text-lg font-semibold">{t('publishing.redeem.rotatingTitle')}</h2>
      <p className="text-muted-foreground text-center text-sm">{t('publishing.redeem.rotatingText')}</p>
      {image.data && link ? (
        // The image carries its own light background: it stays scannable in the dark appearance.
        <img src={image.data} alt={t('publishing.redeem.alt')} width={280} height={280} className="rounded-md"
          data-testid="redeem-rotating-image" />
      ) : image.isError ? (
        <p role="alert" className="text-destructive text-sm">{t('publishing.qr.failed')}</p>
      ) : (
        <div className="bg-muted size-[280px] animate-pulse rounded-md" aria-hidden />
      )}
      {link && (
        <p className="text-muted-foreground text-center text-xs break-all" data-testid="redeem-rotating-link">{link}</p>
      )}
      <p className="text-muted-foreground text-xs" data-testid="redeem-rotating-expires">
        {code.data?.expiresAt ? t('publishing.redeem.rotatingUntil', { time: formatDateTime(code.data.expiresAt) }) : ''}
      </p>
    </section>
  )
}

/**
 * The printed code: lasting, for a poster or a counter card; a scan of it makes a claim the sponsor confirms here
 * within ten minutes. Replaced on request, after which the old posters stop working.
 */
function PrintedCode() {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const [shown, setShown] = useState(false)
  const [saving, setSaving] = useState<string | null>(null)
  const code = useQuery({ queryKey: PRINTED_QUERY, queryFn: () => issueRedeemCode('PRINTED'), enabled: shown,
    staleTime: Infinity })
  const link = code.data ? redeemLink(code.data.redeemKey) : null
  const image = useQuery({ queryKey: ['redeem-qr', link], queryFn: () => qrImageUrl(link!), enabled: !!link,
    staleTime: Infinity })
  const renew = useMutation({
    mutationFn: () => issueRedeemCode('PRINTED', true),
    onSuccess: (issued) => {
      queryClient.setQueryData(PRINTED_QUERY, issued)
      notify.success(t('publishing.redeem.renewed'))
    },
    onError: (error) => notify.error(problemText(error)),
  })
  const save = async (what: string, make: () => Promise<Blob>, name: string) => {
    setSaving(what)
    try {
      const blob = await make()
      download({ blob, name, size: blob.size })
    } catch {
      notify.error(t('publishing.qr.failed'))
    } finally {
      setSaving(null)
    }
  }
  const instructions = LANGUAGES.map((lng) => t('publishing.redeem.posterLine', { lng }))
  const fileBase = t('publishing.redeem.fileName')
  return (
    <section className="flex flex-col gap-3 rounded-lg border p-4" aria-labelledby="redeem-printed-title"
      data-testid="redeem-printed">
      <h2 id="redeem-printed-title" className="text-lg font-semibold">{t('publishing.redeem.printedTitle')}</h2>
      <p className="text-muted-foreground text-sm">{t('publishing.redeem.printedText')}</p>
      {!shown ? (
        <Button type="button" variant="outline" className="w-fit" data-testid="redeem-printed-show"
          onClick={() => setShown(true)}>
          <Printer aria-hidden />
          {t('publishing.redeem.printedShow')}
        </Button>
      ) : code.isError ? (
        <p role="alert" className="text-destructive text-sm">{problemText(code.error)}</p>
      ) : (
        <div className="flex flex-col items-center gap-3">
          {image.data && link ? (
            <img src={image.data} alt={t('publishing.redeem.printedAlt')} width={200} height={200}
              className="rounded-md" data-testid="redeem-printed-image" />
          ) : (
            <div className="bg-muted size-[200px] animate-pulse rounded-md" aria-hidden />
          )}
          {link && <p className="text-muted-foreground text-center text-xs break-all" data-testid="redeem-printed-link">{link}</p>}
          <div className="flex flex-wrap justify-center gap-2">
            <Button type="button" variant="outline" size="sm" disabled={!link || saving !== null}
              aria-busy={saving === 'png' || undefined} data-testid="redeem-printed-png"
              onClick={() => void save('png', () => qrPng(link!), qrFileName(fileBase, 'QR', 'png'))}>
              <Download aria-hidden />
              {t('publishing.qr.png')}
            </Button>
            <Button type="button" variant="outline" size="sm" disabled={!link || saving !== null}
              aria-busy={saving === 'svg' || undefined} data-testid="redeem-printed-svg"
              onClick={() => void save('svg', () => qrSvg(link!), qrFileName(fileBase, 'QR', 'svg'))}>
              <Download aria-hidden />
              {t('publishing.qr.svg')}
            </Button>
            <Button type="button" size="sm" disabled={!link || saving !== null}
              aria-busy={saving === 'poster' || undefined} data-testid="redeem-printed-poster"
              onClick={() => void save('poster', () => posterPng(link!, { name: t('publishing.redeem.posterTitle'),
                instructions }), qrFileName(fileBase, 'poster', 'png'))}>
              <FileImage aria-hidden />
              {t('publishing.qr.poster')}
            </Button>
            <ConfirmDialog title={t('publishing.redeem.renewTitle')} description={t('publishing.redeem.renewText')}
              confirmLabel={t('publishing.redeem.renewConfirm')} cancelLabel={t('publishing.redeem.cancel')} destructive
              onConfirm={() => renew.mutateAsync().then(() => undefined)}
              trigger={<Button type="button" variant="ghost" size="sm" disabled={!link || renew.isPending}
                data-testid="redeem-printed-renew"><RefreshCw aria-hidden />{t('publishing.redeem.renew')}</Button>} />
          </div>
        </div>
      )}
    </section>
  )
}

/** The claims from the printed code waiting for the sponsor: the person at the counter, or not. */
function Claims() {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const claims = useQuery({ queryKey: [...REDEMPTIONS_QUERY, 'claims'],
    queryFn: () => searchRedemptions({ status: 'CLAIMED', offset: 0, limit: 50 }),
    refetchInterval: POLL_EVERY, refetchIntervalInBackground: false })
  const answer = useMutation({
    mutationFn: ({ redemptionId, confirm }: { redemptionId: string; confirm: boolean }) =>
      confirm ? confirmRedemption(redemptionId) : declineRedemption(redemptionId),
    onSuccess: async (_output, { confirm }) => {
      notify.success(t(confirm ? 'publishing.redeem.confirmed' : 'publishing.redeem.declined'))
      await queryClient.invalidateQueries({ queryKey: REDEMPTIONS_QUERY })
    },
    onError: async (error) => {
      notify.error(problemText(error))
      await queryClient.invalidateQueries({ queryKey: REDEMPTIONS_QUERY })
    },
  })
  // A claim past its time to confirm is due again (the server refuses confirming it; the job catches up). The time
  // is the reading's, so the list is pure in the render and moves with each poll.
  const now = claims.dataUpdatedAt
  const items = (claims.data?.items ?? []).filter((row) => !row.confirmBy || new Date(row.confirmBy).getTime() > now)
  if (items.length === 0) return null
  return (
    <Alert variant="warning" data-testid="redeem-claims">
      <Gift aria-hidden />
      <AlertTitle>{t('publishing.redeem.claimsTitle', { count: items.length })}</AlertTitle>
      <AlertDescription className="text-current">
        <ul className="flex flex-col gap-2">
          {items.map((row) => (
            <li key={row.redemptionId} className="flex flex-wrap items-center gap-2" data-testid="redeem-claim"
              data-id={row.redemptionId}>
              <span className="font-mono font-semibold">{row.code}</span>
              <span>{row.takerName ?? '?'}</span>
              <span>{row.description}</span>
              <span className="text-xs">{row.confirmBy ? t('publishing.redeem.claimBy', { time: formatDateTime(row.confirmBy) }) : ''}</span>
              <span className="ml-auto flex gap-1">
                <Button size="sm" disabled={answer.isPending} data-testid="redeem-claim-confirm"
                  onClick={() => answer.mutate({ redemptionId: row.redemptionId, confirm: true })}>
                  <Check aria-hidden />
                  {t('publishing.redeem.confirm')}
                </Button>
                <Button size="sm" variant="outline" disabled={answer.isPending} data-testid="redeem-claim-decline"
                  onClick={() => answer.mutate({ redemptionId: row.redemptionId, confirm: false })}>
                  <X aria-hidden />
                  {t('publishing.redeem.decline')}
                </Button>
              </span>
            </li>
          ))}
        </ul>
      </AlertDescription>
    </Alert>
  )
}

/** The day's redemptions, as they happen: the sponsor sees the scan land. */
function Today() {
  const { t } = useTranslation(SPONSOR)
  const [since] = useState(startOfToday)
  const today = useQuery({ queryKey: [...REDEMPTIONS_QUERY, 'today', since],
    queryFn: () => searchRedemptions({ since, status: 'REDEEMED', offset: 0, limit: 50 }),
    refetchInterval: POLL_EVERY, refetchIntervalInBackground: false })
  const items = today.data?.items ?? []
  return (
    <section className="flex flex-col gap-2" aria-labelledby="redeem-today-title" data-testid="redeem-today">
      <h2 id="redeem-today-title" className="text-lg font-semibold">{t('publishing.redeem.todayTitle')}</h2>
      {items.length === 0 ? (
        <p className="text-muted-foreground text-sm">{t('publishing.redeem.todayNone')}</p>
      ) : (
        <ul className="flex flex-col gap-1 text-sm">
          {items.map((row) => (
            <li key={row.redemptionId} className="flex flex-wrap gap-2" data-testid="redeem-today-item">
              <span className="font-mono">{row.code}</span>
              <span>{row.takerName ?? '?'}</span>
              <span>{row.description}</span>
              <span className="text-muted-foreground">{row.redeemedAt ? formatDateTime(row.redeemedAt) : ''}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** Every prize of the sponsor's, searched and filtered on the server. */
function History() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const statusLabel = useRedemptionStatusLabel()
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState<RedemptionStatus | null>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const rows = useQuery({
    queryKey: [...REDEMPTIONS_QUERY, 'history', q, status, pagination],
    queryFn: () => searchRedemptions({ q, status, offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize }),
    placeholderData: keepPreviousData,
  })
  const columns = useMemo<ColumnDef<RedemptionRow, unknown>[]>(() => [
    { id: 'code', header: t('publishing.redeem.code'), enableSorting: false,
      cell: ({ row }) => <span className="font-mono" data-testid="redemption-code">{row.original.code}</span> },
    { id: 'taker', header: t('publishing.redeem.taker'), enableSorting: false,
      cell: ({ row }) => row.original.takerName ?? <span className="text-muted-foreground">?</span> },
    { id: 'publication', header: t('publishing.redeem.publication'), enableSorting: false,
      cell: ({ row }) => (
        <Link to={publicationPath(row.original.publicationId)} className="underline-offset-4 hover:underline">
          {row.original.publicationName}
        </Link>
      ) },
    { id: 'prize', header: t('publishing.redeem.prize'), enableSorting: false,
      cell: ({ row }) => <span className="whitespace-pre-line">{row.original.description}</span> },
    { id: 'amount', header: t('publishing.redeem.value'), enableSorting: false, meta: { className: 'text-right tabular-nums' },
      cell: ({ row }) => formatKudos(row.original.amount) },
    { id: 'status', header: t('publishing.redeem.status'), enableSorting: false,
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <Badge variant={STATUS_BADGES[row.original.status]} className="w-fit" data-testid="redemption-status"
            data-status={row.original.status}>
            {statusLabel(row.original.status)}
          </Badge>
          {row.original.status === 'DUE' && row.original.expiresAt && (
            <span className="text-muted-foreground text-xs">{t('publishing.redeem.collectBy', { time: formatDateTime(row.original.expiresAt) })}</span>
          )}
          {row.original.status === 'REDEEMED' && row.original.redeemedAt && (
            <span className="text-muted-foreground text-xs">{t('publishing.redeem.collectedAt', { time: formatDateTime(row.original.redeemedAt) })}</span>
          )}
        </span>
      ) },
    { id: 'dueAt', header: t('publishing.redeem.earned'), enableSorting: false,
      cell: ({ row }) => formatDateTime(row.original.dueAt) },
  ], [t, statusLabel])
  const search = (event: FormEvent) => {
    event.preventDefault()
    setQ(text.trim())
    setPagination((p) => ({ ...p, pageIndex: 0 }))
  }
  return (
    <section className="flex flex-col gap-3" aria-labelledby="redeem-history-title" data-testid="redeem-history">
      <h2 id="redeem-history-title" className="text-lg font-semibold">{t('publishing.redeem.historyTitle')}</h2>
      <div className="flex flex-wrap items-end gap-2">
        <form role="search" onSubmit={search} className="flex gap-2">
          <Label htmlFor={`${id}-q`} className="sr-only">{t('publishing.redeem.search')}</Label>
          <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('publishing.redeem.search')}
            data-testid="redemption-search" onChange={(event) => setText(event.target.value)} />
          <Button type="submit" variant="outline" size="icon" aria-label={t('publishing.redeem.search')}>
            <Search aria-hidden />
          </Button>
        </form>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-status`} className="sr-only">{t('publishing.redeem.status')}</Label>
          <Select value={status ?? 'ALL'} onValueChange={(value) => {
            setStatus(value === 'ALL' ? null : (value as RedemptionStatus))
            setPagination((p) => ({ ...p, pageIndex: 0 }))
          }}>
            <SelectTrigger id={`${id}-status`} className="w-56" data-testid="redemption-status-filter">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">{t('publishing.redeem.allStatuses')}</SelectItem>
              {REDEMPTION_STATUSES.map((code) => <SelectItem key={code} value={code}>{statusLabel(code)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </div>
      {rows.isError ? (
        <PageState kind="error" description={problemText(rows.error)}
          action={<Button variant="outline" onClick={() => void rows.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <DataTable label={t('publishing.redeem.historyTitle')} columns={columns} data={rows.data?.items ?? []}
          getRowId={(row) => row.redemptionId} loading={rows.isPending} stale={rows.isPlaceholderData}
          empty={t('publishing.redeem.none')} pagination={pagination} onPaginationChange={setPagination}
          rowCount={rows.data?.total}
          rowProps={(row) => ({ 'data-testid': 'redemption-row', 'data-status': row.status })} />
      )}
    </section>
  )
}

/**
 * The redeem page (docs/quizbuks/plans/Q22-reward-methods.md, Q22b): the code on the screen takers scan to collect
 * their prize at once; the printed code for posters and counter cards, whose claims are confirmed here; what was
 * collected today; and every prize of the sponsor's.
 */
export function RedeemPage() {
  const { t } = useTranslation(SPONSOR)
  return (
    <section className="flex flex-col gap-6" data-testid="redeem-page">
      <PageHeader title={t('publishing.redeem.title')} description={t('publishing.redeem.intro')} />
      <Claims />
      <div className="grid gap-4 md:grid-cols-2">
        <RotatingCode />
        <PrintedCode />
      </div>
      <Today />
      <History />
    </section>
  )
}
