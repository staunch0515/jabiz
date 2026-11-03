import { formatDate, formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  DataTable,
  DescriptionList,
  type ColumnDef,
  type PaginationState,
  type SortingState,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileDown, Wallet } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import {
  downloadInvoiceDocument,
  INVOICE_UNPAID,
  payEarly,
  payInvoice,
  readInvoiceStatuses,
  searchFeeInvoices,
  WALLET_QUERY,
  type InvoiceRow,
  type InvoiceStatus,
  type SponsorWallet,
} from './api'
import './messages'

const PAGE_SIZE = 10

const STATUS_BADGES: Record<InvoiceStatus, 'success' | 'secondary' | 'outline' | 'warning' | 'destructive'> = {
  OPEN: 'warning',
  OVERDUE: 'destructive',
  PAID: 'success',
  VOID: 'outline',
}

/** The labels of the invoice states, from the server's dictionary (the code until it answered). */
function useInvoiceStatusLabel() {
  const statuses = useQuery({ queryKey: ['qb', 'dict', 'fee-invoice-status'], queryFn: readInvoiceStatuses,
    staleTime: Infinity })
  return (code: string) => statuses.data?.find((item) => item.code === code)?.label ?? code
}

/**
 * The platform's invoices to the sponsor (Q87, `qb.sponsor.fee-invoices`): paid from the cash account when open
 * (`QB_FEE_INVOICE_PAY`; the server checks the state and the cash), their document issued once and downloaded as kept
 * (`QB_FEE_INVOICE_DOCUMENT`); the postpaid fees not invoiced yet paid early (`QB_FEE_PAY_EARLY`, each getting an
 * invoice of its own). The page records nothing itself.
 */
export function Invoices({ wallet }: { wallet: SponsorWallet }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const statusLabel = useInvoiceStatusLabel()
  const queryClient = useQueryClient()
  const [notice, setNotice] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>([{ id: 'issuedAt', desc: true }])
  const invoices = useQuery({
    queryKey: [...WALLET_QUERY, 'invoices', pagination, sorting],
    queryFn: () => searchFeeInvoices({
      offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize,
      sorts: sorting.map((s) => ({ field: s.id, asc: !s.desc })),
    }),
    placeholderData: keepPreviousData,
  })
  const pay = useMutation({
    mutationFn: (invoiceId: string) => payInvoice(invoiceId),
    onMutate: () => setNotice(null),
    onSuccess: async (output) => {
      setNotice({ kind: 'ok', text: t('wallet.invoices.paid', { number: output.invoiceNo, cash: formatKudos(output.cashBalance) }) })
      await queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    },
    onError: (error) => setNotice({ kind: 'error', text: problemText(error) }),
  })
  const early = useMutation({
    mutationFn: () => payEarly(),
    onMutate: () => setNotice(null),
    onSuccess: async (output) => {
      setNotice({ kind: 'ok', text: t('wallet.invoices.paidEarly', { count: output.count, amount: formatKudos(output.total),
        cash: formatKudos(output.cashBalance) }) })
      await queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    },
    onError: (error) => setNotice({ kind: 'error', text: problemText(error) }),
  })
  const download = useMutation({
    mutationFn: (invoiceId: string) => downloadInvoiceDocument(invoiceId, i18n.language),
    onMutate: () => setNotice(null),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...WALLET_QUERY, 'invoices'] }),
    onError: (error) => setNotice({ kind: 'error', text: problemText(error) }),
  })
  const cashAvailable = Math.max(0, wallet.cashBalance - wallet.cashHeld)
  const columns = useMemo<ColumnDef<InvoiceRow, unknown>[]>(() => [
    { id: 'issuedAt', header: t('wallet.invoices.issued'), meta: { card: 'subtitle' },
      cell: ({ row }) => formatDateTime(row.original.issuedAt) },
    { id: 'invoiceNo', header: t('wallet.invoices.number'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => <span data-testid="invoice-row-number">{row.original.invoiceNo}</span> },
    { id: 'period', header: t('wallet.invoices.period'), enableSorting: false,
      cell: ({ row }) => `${row.original.period} · ${t(`wallet.invoices.billingMode.${row.original.billingMode}`)}` },
    { id: 'total', header: t('wallet.invoices.total'), meta: { className: 'text-right' },
      cell: ({ row }) => <span data-testid="invoice-row-total">{formatKudos(row.original.total)}</span> },
    { id: 'dueOn', header: t('wallet.invoices.due'),
      cell: ({ row }) => (row.original.dueOn ? formatDate(row.original.dueOn) : '—') },
    { id: 'status', header: t('wallet.invoices.status'), meta: { card: 'badge' },
      cell: ({ row }) => (
        <Badge variant={STATUS_BADGES[row.original.status]} data-testid="invoice-row-status" data-status={row.original.status}>
          {statusLabel(row.original.status)}
        </Badge>
      ) },
    { id: 'actions', header: () => <span className="sr-only">{t('wallet.invoices.document')}</span>, enableSorting: false,
      meta: { card: 'actions' },
      cell: ({ row }) => {
        const invoice = row.original
        const unpaid = INVOICE_UNPAID.includes(invoice.status)
        const covered = cashAvailable >= invoice.total
        return (
          <span className="flex flex-wrap justify-end gap-2">
            {unpaid && (
              <Button size="sm" disabled={!covered || pay.isPending} aria-busy={pay.isPending || undefined}
                data-testid="invoice-row-pay" onClick={() => pay.mutate(invoice.invoiceId)}>
                <Wallet aria-hidden />
                {covered ? t('wallet.invoices.pay') : t('wallet.invoices.payShort', { cash: formatKudos(cashAvailable) })}
                <span className="sr-only">: {invoice.invoiceNo}</span>
              </Button>
            )}
            {invoice.status !== 'VOID' && (
              <Button size="sm" variant="outline" disabled={download.isPending} aria-busy={download.isPending || undefined}
                data-testid="invoice-row-document" onClick={() => download.mutate(invoice.invoiceId)}>
                <FileDown aria-hidden />
                {t('wallet.invoices.document')}
                <span className="sr-only">: {invoice.invoiceNo}</span>
              </Button>
            )}
          </span>
        )
      } },
  ], [t, statusLabel, cashAvailable, pay, download])
  return (
    <section aria-labelledby="wallet-invoices" className="flex flex-col gap-3" data-testid="wallet-invoices">
      <h2 id="wallet-invoices" className="text-lg font-semibold">{t('wallet.invoices.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('wallet.invoices.intro')}</p>
      {wallet.overdueCount > 0 ? (
        <Alert variant="destructive" data-testid="wallet-overdue">
          <AlertDescription>{t('wallet.invoices.overdueAlert', { count: wallet.overdueCount })}</AlertDescription>
        </Alert>
      ) : wallet.unpaidCount > 0 && (
        <Alert variant="warning" data-testid="wallet-unpaid">
          <AlertDescription>{t('wallet.invoices.unpaidAlert', { amount: formatKudos(wallet.unpaidTotal) })}</AlertDescription>
        </Alert>
      )}
      <DescriptionList columns={3} data-testid="wallet-billing" data-mode={wallet.billingMode} items={[
        { key: 'unpaid', label: t('wallet.invoices.unpaid'), value: wallet.unpaidCount > 0
          ? t('wallet.invoices.unpaidValue', { amount: formatKudos(wallet.unpaidTotal), count: wallet.unpaidCount })
          : t('wallet.invoices.none') },
        { key: 'billing', label: t('wallet.invoices.billing'), value: t(`wallet.invoices.billingModes.${wallet.billingMode}`) },
        ...(wallet.billingMode === 'POSTPAID'
          ? [{ key: 'credit', label: t('wallet.invoices.creditLimit'), value: formatKudos(wallet.creditLimit) }] : []),
        ...(wallet.uninvoicedTotal > 0
          ? [{ key: 'uninvoiced', label: t('wallet.invoices.uninvoiced'), value: formatKudos(wallet.uninvoicedTotal) }] : []),
      ]} />
      {wallet.uninvoicedTotal > 0 && (
        <div className="flex flex-wrap items-center gap-3" data-testid="wallet-uninvoiced">
          <p className="text-muted-foreground text-sm">{t('wallet.invoices.uninvoicedHint', { amount: formatKudos(wallet.uninvoicedTotal) })}</p>
          <Button size="sm" variant="outline" disabled={cashAvailable < wallet.uninvoicedTotal || early.isPending}
            aria-busy={early.isPending || undefined} data-testid="invoice-pay-early" onClick={() => early.mutate()}>
            <Wallet aria-hidden />
            {cashAvailable >= wallet.uninvoicedTotal ? t('wallet.invoices.payEarly')
              : t('wallet.invoices.payShort', { cash: formatKudos(cashAvailable) })}
          </Button>
        </div>
      )}
      {notice && (
        <Alert variant={notice.kind === 'error' ? 'destructive' : undefined} data-testid="invoice-notice">
          <AlertDescription>{notice.text}</AlertDescription>
        </Alert>
      )}
      {invoices.isError
        ? <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p>
        : (
          <DataTable label={t('wallet.invoices.table')} columns={columns} data={invoices.data?.items ?? []}
            storageKey="qb.sponsor.fee-invoices" getRowId={(row) => row.invoiceId} loading={invoices.isPending}
            stale={invoices.isPlaceholderData} empty={t('wallet.invoices.none')} pagination={pagination}
            onPaginationChange={setPagination} rowCount={invoices.data?.total} sorting={sorting}
            onSortingChange={(next) => {
              setSorting(next)
              setPagination((p) => ({ ...p, pageIndex: 0 }))
            }}
            rowProps={(row) => ({ 'data-testid': 'invoice-row', 'data-invoice': row.invoiceId, 'data-status': row.status })} />
        )}
    </section>
  )
}
