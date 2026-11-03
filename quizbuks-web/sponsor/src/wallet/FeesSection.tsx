import { Alert, AlertDescription, Badge, Button, DataTable, DescriptionList, notify, type ColumnDef } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileDown } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { problemText } from '../qb/problems'
import { WALLET_QUERY } from './api'
import {
  downloadInvoice,
  FEE_QUERY,
  owedOutsideInvoices,
  payFees,
  readFeeStanding,
  readInvoices,
  UNPAID,
  type InvoiceRow,
  type InvoiceStatus,
} from './fees'
import './feeMessages'

const STATUS_BADGES: Record<InvoiceStatus, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  PAID: 'secondary',
  ISSUED: 'default',
  OVERDUE: 'destructive',
  VOID: 'outline',
}

/**
 * Q87b: the platform fees on the wallet page - how the sponsor pays (set by their operator or the platform), what is
 * owed, the invoices with "Pay" (from the cash account) and their PDF, and paying early what is not invoiced yet.
 * Shown once there is something to show; the server decides every payment.
 */
export function FeesSection() {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const [problem, setProblem] = useState<unknown>(null)
  const standing = useQuery({ queryKey: [...FEE_QUERY, 'standing'], queryFn: readFeeStanding })
  const invoices = useQuery({ queryKey: [...FEE_QUERY, 'invoices'], queryFn: () => readInvoices() })
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: FEE_QUERY })
    await queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
  }
  const pay = useMutation({
    mutationFn: (invoiceId: string | null) => payFees(invoiceId),
    onMutate: () => setProblem(null),
    onError: setProblem,
    onSuccess: async () => {
      notify.success(t('fees.paid'))
      await refresh()
    },
  })
  const download = useMutation({
    mutationFn: (invoice: InvoiceRow) => downloadInvoice(invoice, i18n.language),
    onMutate: () => setProblem(null),
    onError: setProblem,
  })

  const columns = useMemo<ColumnDef<InvoiceRow, unknown>[]>(() => [
    { id: 'invoiceNo', header: t('fees.number'), meta: { card: 'title' },
      cell: ({ row }) => <span data-testid="invoice-row-no">{row.original.invoiceNo}</span> },
    { id: 'period', header: t('fees.period'), meta: { card: 'subtitle' }, cell: ({ row }) => row.original.period },
    { id: 'total', header: t('fees.total'), meta: { className: 'text-right' },
      cell: ({ row }) => formatKudos(row.original.total) },
    { id: 'due', header: t('fees.due'), meta: { className: 'text-right' },
      cell: ({ row }) => <span data-testid="invoice-row-due">{formatKudos(row.original.due)}</span> },
    { id: 'dueOn', header: t('fees.dueOn'), cell: ({ row }) => row.original.dueOn ?? '—' },
    { id: 'status', header: t('fees.status'), meta: { card: 'badge' },
      cell: ({ row }) => (
        <Badge variant={STATUS_BADGES[row.original.status]} data-testid="invoice-row-status"
          data-status={row.original.status}>
          {t(`fees.statuses.${row.original.status}`)}
        </Badge>
      ) },
    { id: 'actions', header: () => <span className="sr-only">{t('fees.download')}</span>, meta: { card: 'actions' },
      cell: ({ row }) => (
        <span className="flex flex-wrap justify-end gap-2">
          {UNPAID.includes(row.original.status) && (
            <Button size="sm" disabled={pay.isPending} aria-busy={pay.isPending || undefined}
              data-testid="invoice-row-pay" onClick={() => pay.mutate(row.original.invoiceId)}>
              {t('fees.pay')}<span className="sr-only">: {row.original.invoiceNo}</span>
            </Button>
          )}
          <Button size="sm" variant="outline" disabled={download.isPending}
            aria-busy={download.isPending || undefined} data-testid="invoice-row-download"
            onClick={() => download.mutate(row.original)}>
            <FileDown aria-hidden />
            {t('fees.download')}<span className="sr-only">: {row.original.invoiceNo}</span>
          </Button>
        </span>
      ) },
  ], [t, pay, download])

  const data = standing.data
  const rows = invoices.data ?? []
  if (!data || (rows.length === 0 && data.unpaid <= 0 && data.billingMode === 'PREPAID')) return null
  const owed = owedOutsideInvoices(data, rows)
  return (
    <section aria-labelledby="wallet-fees" className="flex flex-col gap-3" data-testid="wallet-fees">
      <h2 id="wallet-fees" className="text-lg font-semibold">{t('fees.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('fees.intro')}</p>
      {data.overdue && (
        <Alert variant="destructive" data-testid="fees-overdue">
          <AlertDescription>{t('fees.overdue')}</AlertDescription>
        </Alert>
      )}
      <DescriptionList columns={3} items={[
        { key: 'mode', label: t('fees.mode'),
          value: <span data-testid="fees-mode" data-mode={data.billingMode}>{t(`fees.modes.${data.billingMode}`)}</span> },
        ...(data.billingMode === 'POSTPAID'
          ? [{ key: 'credit', label: t('fees.credit'), value: formatKudos(data.creditLimit) }] : []),
        { key: 'unpaid', label: t('fees.unpaid'),
          value: <span data-testid="fees-unpaid">{formatKudos(data.unpaid)}</span> },
      ]} />
      {owed > 0 && (
        <div className="flex flex-wrap items-center gap-3 rounded-md border p-3" data-testid="fees-owed">
          <p className="text-sm">{t('fees.owed', { amount: formatKudos(owed) })}</p>
          <Button size="sm" disabled={pay.isPending} aria-busy={pay.isPending || undefined}
            data-testid="fees-pay-owed" onClick={() => pay.mutate(null)}>
            {t('fees.payOwed')}
          </Button>
        </div>
      )}
      {problem !== null && (
        <Alert variant="destructive" data-testid="fees-problem">
          <AlertDescription>{problemText(problem)}</AlertDescription>
        </Alert>
      )}
      <h3 className="font-medium">{t('fees.invoices')}</h3>
      <DataTable label={t('fees.invoicesTable')} columns={columns} data={rows} loading={invoices.isPending}
        getRowId={(row) => row.invoiceId} empty={t('fees.noInvoices')}
        rowProps={(row) => ({ 'data-testid': 'invoice-row', 'data-invoice': row.invoiceId })} />
    </section>
  )
}
