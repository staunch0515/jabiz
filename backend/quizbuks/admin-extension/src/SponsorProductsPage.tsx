import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  DataTable,
  EXTENSION_NAMESPACE,
  Label,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  paths,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { httpsUrl } from './links'
import { QUEUE, usePaged, useLabels, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION, SPONSOR_PRODUCT_PROCESS, SPONSOR_PRODUCT_STATUSES } from './ids'
import { formatShopPrice, readSponsorProducts, type SponsorProductRow } from './product-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const ALL = 'ALL'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { name: 'name', sponsor: 'company', editedAt: 'editedAt' } as const

/**
 * The process that takes a product down or puts it back for the caller: headquarters', or the operator's (which the
 * server limits to the sponsors the operator looks after, and to its own takedowns); none for a reader who may do
 * neither.
 */
function useProcesses() {
  const { can } = useAuth()
  if (can(PERMISSION.contentWrite)) {
    return { takedown: SPONSOR_PRODUCT_PROCESS.takedown, restore: SPONSOR_PRODUCT_PROCESS.restore, operator: false }
  }
  if (can(PERMISSION.operatorUse)) {
    return { takedown: SPONSOR_PRODUCT_PROCESS.operatorTakedown, restore: SPONSOR_PRODUCT_PROCESS.operatorRestore,
      operator: true }
  }
  return null
}

/**
 * Sponsors' products in a table (Q60b-3): every one, or one sponsor's (on their page's view). Each opens the shop in
 * a new tab; a shown or hidden one is taken down, and one taken down put back, through the platform's process page
 * with the product filled in, where the reason is given with a second factor.
 */
export function SponsorProductsTable({ userId, status = null }: { userId?: string; status?: string | null }) {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const statusLabel = useLabels(DICTIONARY.sponsorProductStatus)
  const processes = useProcesses()
  const { query, table, sortable } = usePaged([...QUEUE.sponsorProducts, userId ?? null, status],
    (offset, limit, sorts) => readSponsorProducts(offset, limit, sorts, status, userId), true, SORTS)
  const columns = useMemo<ColumnDef<SponsorProductRow, unknown>[]>(() => {
    const link = (name: string, productId: string) =>
      `${paths.process(name, SPONSOR_PRODUCT_PROCESS.version)}?${new URLSearchParams({ productId })}`
    return [
      {
        id: 'name',
        header: t('sponsorProducts.columns.name'),
        cell: ({ row }) => (
          <span className="flex flex-col">
            <span className="font-medium">{row.original.name}</span>
            {httpsUrl(row.original.shopUrl)
              ? <a href={row.original.shopUrl} target="_blank" rel="noopener noreferrer nofollow"
                className="text-muted-foreground text-xs break-all underline" data-testid="sponsor-product-shop">
                {row.original.shopUrl}</a>
              : <span className="text-muted-foreground text-xs break-all">{row.original.shopUrl}</span>}
          </span>
        ),
      },
      ...(userId ? [] : [{
        id: 'sponsor',
        header: t('sponsorProducts.columns.sponsor'),
        cell: ({ row }: { row: { original: SponsorProductRow } }) => (
          <Link to={PATH.sponsorPage(row.original.userId)} className={linkClass} data-testid="sponsor-product-sponsor">
            {row.original.company ?? row.original.handle ?? row.original.userId}
          </Link>
        ),
      }]),
      { id: 'price', header: t('sponsorProducts.columns.price'), meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => formatShopPrice(row.original.price, row.original.currency, i18n.language) },
      {
        id: 'status',
        header: t('sponsorProducts.columns.status'),
        cell: ({ row }) => (
          <span className="flex flex-col gap-1">
            <StatusBadge code={row.original.status} label={statusLabel(row.original.status)} />
            {row.original.takedownReason && (
              <span className="text-muted-foreground text-xs" data-testid="sponsor-product-reason">
                {t('sponsorProducts.reason', { reason: row.original.takedownReason })}</span>
            )}
          </span>
        ),
      },
      { id: 'editedAt', header: t('sponsorProducts.columns.editedAt'), cell: ({ row }) => when(row.original.editedAt) },
      ...(processes ? [{
        id: 'actions',
        header: t('sponsorProducts.columns.actions'),
        cell: ({ row }: { row: { original: SponsorProductRow } }) => (row.original.status === 'TAKEN_DOWN'
          // An operator puts back only its own takedowns (D-Q48-5): headquarters' is shown as such.
          ? processes.operator && !row.original.takedownOperatorId
            ? <span className="text-muted-foreground text-xs" data-testid="sponsor-product-by-hq">
              {t('sponsorProducts.byHq')}</span>
            : <Link to={link(processes.restore, row.original.productId)} className={linkClass}
            aria-label={t('sponsorProducts.restoreOf', { name: row.original.name })}
            data-testid="sponsor-product-restore">{t('sponsorProducts.restore')}</Link>
          : <Link to={link(processes.takedown, row.original.productId)} className={linkClass}
            aria-label={t('sponsorProducts.takedownOf', { name: row.original.name })}
            data-testid="sponsor-product-takedown">{t('sponsorProducts.takedown')}</Link>),
      }] : []),
    ]
  }, [t, i18n.language, statusLabel, processes, userId])
  if (query.isError) return <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
  return (
    <DataTable label={userId ? t('sponsorProducts.sponsorTable') : t('sponsorProducts.table')}
      columns={sortable(columns)} {...table} getRowId={(row) => row.productId} empty={<EmptyState art="search" title={t('sponsorProducts.none')} />}
      rowProps={(row) => ({ 'data-testid': 'sponsor-product-row', 'data-id': row.productId, 'data-status': row.status })} />
  )
}

/**
 * Every sponsor's products (Q60b-3; docs/quizbuks/plans/Q60-sponsor-page.md §5), last edited first, by status. An
 * operator's session lists its own sponsors' only (the server limits the template).
 */
export default function SponsorProductsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const statusLabel = useLabels(DICTIONARY.sponsorProductStatus)
  const [status, setStatus] = useState<string>(ALL)
  return (
    <div className="flex flex-col gap-4" data-testid="qb-sponsor-products">
      <PageHeader title={t('sponsorProducts.title')} description={t('sponsorProducts.description')} className="pb-0" />
      <div className="flex items-center gap-2">
        <Label htmlFor="sponsor-products-status">{t('sponsorProducts.status')}</Label>
        <Select value={status} onValueChange={setStatus}>
          <SelectTrigger id="sponsor-products-status" className="w-48" data-testid="sponsor-products-status">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>{t('sponsorProducts.allStatuses')}</SelectItem>
            {SPONSOR_PRODUCT_STATUSES.map((code) => <SelectItem key={code} value={code}>{statusLabel(code)}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      <SponsorProductsTable status={status === ALL ? null : status} />
    </div>
  )
}
