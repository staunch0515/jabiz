import { formatDate } from '@jabiz/client'
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
  feeAccountAvailable,
  INVOICE_UNPAID,
  invoiceOwed,
  payEarly,
  payInvoice,
  readInvoiceSources,
  readInvoiceStatuses,
  searchFeeInvoices,
  WALLET_QUERY,
  type InvoiceRow,
  type InvoiceStatus,
  type SponsorWallet,
} from './api'
import { CreditNotes } from './CreditNotes'
import './messages'

const PAGE_SIZE = 10

const STATUS_BADGES: Record<InvoiceStatus, 'success' | 'secondary' | 'outline' | 'warning' | 'destructive'> = {
  OPEN: 'warning',
  PARTIAL: 'warning',
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
 * The platform's invoices to the sponsor (Q87, `qb.sponsor.fee-invoices`): paid from the account that pays the
 * platform's fees when open (`QB_FEE_INVOICE_PAY`; F-M1: the granted credit first, then the cash, as far as they go,
 * the rest staying owed; the server checks the state and allocates), their document issued once and downloaded as kept
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
      setNotice({ kind: 'ok', text: output.status === 'PAID'
        ? t('wallet.invoices.paid', { number: output.invoiceNo, cash: formatKudos(output.cashBalance) })
        : t('wallet.invoices.paidPart', { number: output.invoiceNo, amount: formatKudos(output.paidNow),
          owed: formatKudos(invoiceOwed({ total: output.total, paidAmount: output.paidAmount,
            creditApplied: invoices.data?.items.find((item) => item.invoiceId === output.invoiceId)?.creditApplied ?? 0 })) }) })
      await queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    },
    onError: (error) => setNotice({ kind: 'error', text: problemText(error) }),
  })
  const early = useMutation({
    mutationFn: () => payEarly(),
    onMutate: () => setNotice(null),
    onSuccess: async (output) => {
      setNotice({ kind: 'ok', text: t(output.remaining > 0 ? 'wallet.invoices.paidEarlyMore' : 'wallet.invoices.paidEarly',
        { count: output.count, amount: formatKudos(output.total), cash: formatKudos(output.cashBalance), remaining: output.remaining }) })
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
  // F-M1: the granted credit and the cash not held by a dispute; the server allocates.
  const accountAvailable = feeAccountAvailable(wallet)
  const columns = useMemo<ColumnDef<InvoiceRow, unknown>[]>(() => [
    { id: 'issuedAt', header: t('wallet.invoices.issued'), meta: { card: 'subtitle' },
      cell: ({ row }) => formatDate(row.original.issuedOn || row.original.issuedAt) },
    { id: 'invoiceNo', header: t('wallet.invoices.number'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => <span data-testid="invoice-row-number">{row.original.invoiceNo}</span> },
    { id: 'period', header: t('wallet.invoices.period'), enableSorting: false,
      cell: ({ row }) => `${row.original.period} · ${t(`wallet.invoices.billingMode.${row.original.billingMode}`)}` },
    { id: 'total', header: t('wallet.invoices.total'), meta: { className: 'text-right' },
      cell: ({ row }) => <span data-testid="invoice-row-total">{formatKudos(row.original.total)}</span> },
    // F-M1: an invoice may be paid in part; what is paid of it.
    { id: 'paidAmount', header: t('wallet.invoices.paidAmount'), enableSorting: false, meta: { className: 'text-right' },
      cell: ({ row }) => (
        <>
          <span data-testid="invoice-row-paid">{formatKudos(row.original.paidAmount)}</span>
          {/* F-M7: what credit notes paid of the total, apart from what was paid from the account. */}
          {row.original.creditApplied > 0 && (
            <span className="text-muted-foreground block text-xs" data-testid="invoice-row-credit-applied">
              {t('wallet.invoices.creditApplied', { amount: formatKudos(row.original.creditApplied) })}
            </span>
          )}
        </>
      ) },
    { id: 'dueOn', header: t('wallet.invoices.due'),
      cell: ({ row }) => (row.original.dueOn ? formatDate(row.original.dueOn) : '—') },
    { id: 'status', header: t('wallet.invoices.status'), meta: { card: 'badge' },
      cell: ({ row }) => (
        <>
          <Badge variant={STATUS_BADGES[row.original.status]} data-testid="invoice-row-status" data-status={row.original.status}>
            {statusLabel(row.original.status)}
          </Badge>
          {/* F-M13: the day it was voided; the reason is the headquarters' only. */}
          {row.original.status === 'VOID' && row.original.voidedAt && (
            <span className="text-muted-foreground block text-xs" data-testid="invoice-row-voided-on">
              {t('wallet.invoices.voidedOn', { date: formatDate(row.original.voidedAt) })}
            </span>
          )}
        </>
      ) },
    { id: 'actions', header: () => <span className="sr-only">{t('wallet.invoices.document')}</span>, enableSorting: false,
      meta: { card: 'actions' },
      cell: ({ row }) => {
        const invoice = row.original
        const unpaid = INVOICE_UNPAID.includes(invoice.status)
        const owed = invoiceOwed(invoice)
        const covered = accountAvailable >= owed
        return (
          <span className="flex flex-wrap justify-end gap-2">
            {unpaid && (
              <Button size="sm" disabled={accountAvailable <= 0 || pay.isPending} aria-busy={pay.isPending || undefined}
                data-testid="invoice-row-pay" data-covered={covered} onClick={() => pay.mutate(invoice.invoiceId)}>
                <Wallet aria-hidden />
                {covered ? t('wallet.invoices.pay')
                  : accountAvailable > 0 ? t('wallet.invoices.payPart', { amount: formatKudos(accountAvailable) })
                    : t('wallet.invoices.payShort', { cash: formatKudos(accountAvailable) })}
                <span className="sr-only">: {invoice.invoiceNo}</span>
              </Button>
            )}
            {/* F-M13: a voided invoice's original stays downloadable once it was issued; none is issued after. */}
            {(invoice.status !== 'VOID' || invoice.documentRunId) && (
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
  ], [t, statusLabel, accountAvailable, pay, download])
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
          <Button size="sm" variant="outline" disabled={accountAvailable < wallet.uninvoicedTotal || early.isPending}
            aria-busy={early.isPending || undefined} data-testid="invoice-pay-early" onClick={() => early.mutate()}>
            <Wallet aria-hidden />
            {accountAvailable >= wallet.uninvoicedTotal ? t('wallet.invoices.payEarly')
              : t('wallet.invoices.payShort', { cash: formatKudos(accountAvailable) })}
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
            renderExpanded={(row) => <InvoiceSources invoiceId={row.invoiceId} total={row.total} />}
            rowProps={(row) => ({ 'data-testid': 'invoice-row', 'data-invoice': row.invoiceId, 'data-status': row.status })} />
        )}
      <CreditNotes />
    </section>
  )
}

/**
 * F-M13: what an invoice was paid with, as of now (the document's own is as of the day it was issued, and does not
 * change): the same statement as the document's, so the two never disagree on how it is counted.
 */
function InvoiceSources({ invoiceId, total }: { invoiceId: string; total: number }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const sources = useQuery({ queryKey: [...WALLET_QUERY, 'invoice-sources', invoiceId],
    queryFn: () => readInvoiceSources(invoiceId) })
  if (sources.isError) return <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p>
  if (!sources.data) return null
  const s = sources.data
  return (
    <div data-testid="invoice-sources" data-total={total}>
      <h3 className="mb-2 text-sm font-semibold">{t('wallet.invoices.sources.title')}</h3>
      <DescriptionList columns={3} items={[
        ...(s.gift === null || s.prepayment === null || s.cash === null ? [] : [
          { key: 'gift', label: t('wallet.invoices.sources.gift'), value: formatKudos(s.gift) },
          { key: 'prepayment', label: t('wallet.invoices.sources.prepayment'),
            value: formatKudos(s.prepayment) + ((s.prepaymentGift ?? 0) > 0
              ? ` (${t('wallet.invoices.sources.prepaymentGift', { amount: formatKudos(s.prepaymentGift ?? 0) })})` : '') },
          { key: 'cash', label: t('wallet.invoices.sources.cash'), value: formatKudos(s.cash) },
        ]),
        { key: 'creditNote', label: t('wallet.invoices.sources.creditNote'), value: formatKudos(s.creditNote) },
        { key: 'unpaid', label: t('wallet.invoices.sources.unpaid'), value: formatKudos(s.unpaid) },
      ]} />
      {s.gift === null && (
        <p className="text-muted-foreground mt-2 text-sm" data-testid="invoice-sources-unavailable">
          {t('wallet.invoices.sources.unavailable')}
        </p>
      )}
      <p className="text-muted-foreground mt-2 text-xs">{t('wallet.invoices.sources.note')}</p>
    </div>
  )
}
