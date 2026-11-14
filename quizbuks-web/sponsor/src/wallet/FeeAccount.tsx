import { formatDateTime } from '@jabiz/client'
import { DataTable, DescriptionList, type ColumnDef, type PaginationState } from '@jabiz/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import {
  readCashSources,
  readFeeAccountLots,
  readFeeCreditSources,
  readFeePayments,
  readFeeSubAccounts,
  WALLET_QUERY,
  type FeeAccountLot,
  type FeePaymentPart,
  type SponsorWallet,
} from './api'
import './messages'

const PAGE_SIZE = 10

/** A dictionary's labels from the server (the code until it answered). */
function useLabels(key: string, read: () => Promise<{ code: string; label: string }[]>) {
  const items = useQuery({ queryKey: ['qb', 'dict', key], queryFn: read, staleTime: Infinity })
  return (code: string | null) => (code == null ? '' : items.data?.find((item) => item.code === code)?.label ?? code)
}

/**
 * F-M1: the account that pays the platform's fees (`qb.sponsor.wallet`, `qb.sponsor.fee-account-lots`,
 * `qb.sponsor.fee-payments`): the total, the granted credit and the cash; what is left of each grant and each sum of
 * cash, in the order the account is spent (with each grant's expiry); and how each fee was paid. Read only: the page
 * shows what the server gives, it allocates nothing.
 */
export function FeeAccount({ wallet }: { wallet: SponsorWallet }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const subAccount = useLabels('fee-sub-account', readFeeSubAccounts)
  const creditSource = useLabels('fee-credit-source', readFeeCreditSources)
  const cashSource = useLabels('cash-source', readCashSources)
  const [lotPage, setLotPage] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [paymentPage, setPaymentPage] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const lots = useQuery({
    queryKey: [...WALLET_QUERY, 'fee-account-lots', lotPage],
    queryFn: () => readFeeAccountLots(lotPage.pageIndex * lotPage.pageSize, lotPage.pageSize),
    placeholderData: keepPreviousData,
  })
  const payments = useQuery({
    queryKey: [...WALLET_QUERY, 'fee-payments', paymentPage],
    queryFn: () => readFeePayments(paymentPage.pageIndex * paymentPage.pageSize, paymentPage.pageSize),
    placeholderData: keepPreviousData,
  })
  const sourceOf = useMemo(() => (sub: 'FEE_CREDIT' | 'CASH', source: string | null) =>
    (sub === 'FEE_CREDIT' ? creditSource(source) : cashSource(source)), [creditSource, cashSource])
  const lotColumns = useMemo<ColumnDef<FeeAccountLot, unknown>[]>(() => [
    { id: 'seq', header: t('wallet.feeAccount.order'), enableSorting: false, meta: { className: 'text-right' },
      cell: ({ row }) => row.original.seq },
    { id: 'subAccount', header: t('wallet.feeAccount.subAccount'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => subAccount(row.original.subAccount) },
    { id: 'source', header: t('wallet.feeAccount.source'), enableSorting: false, meta: { card: 'subtitle' },
      cell: ({ row }) => sourceOf(row.original.subAccount, row.original.source) },
    { id: 'arrivedAt', header: t('wallet.feeAccount.arrived'), enableSorting: false,
      cell: ({ row }) => (row.original.arrivedAt ? formatDateTime(row.original.arrivedAt) : '—') },
    { id: 'expiresAt', header: t('wallet.feeAccount.expires'), enableSorting: false,
      cell: ({ row }) => (row.original.expiresAt ? formatDateTime(row.original.expiresAt) : '—') },
    // F-M2: what of each lot was spent (the amount less what is left).
    { id: 'used', header: t('wallet.feeAccount.used'), enableSorting: false, meta: { className: 'text-right' },
      cell: ({ row }) => <span data-testid="fee-lot-used">{formatKudos(row.original.amount - row.original.remaining)}</span> },
    { id: 'remaining', header: t('wallet.feeAccount.remaining'), enableSorting: false, meta: { card: 'badge', className: 'text-right' },
      cell: ({ row }) => <span data-testid="fee-lot-remaining">{formatKudos(row.original.remaining)}</span> },
  ], [t, subAccount, sourceOf])
  const paymentColumns = useMemo<ColumnDef<FeePaymentPart, unknown>[]>(() => [
    { id: 'paidAt', header: t('wallet.feeAccount.paidAt'), enableSorting: false, meta: { card: 'subtitle' },
      cell: ({ row }) => (row.original.paidAt ? formatDateTime(row.original.paidAt) : '—') },
    { id: 'invoiceNo', header: t('wallet.feeAccount.invoice'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => row.original.invoiceNo ?? '—' },
    { id: 'subAccount', header: t('wallet.feeAccount.subAccount'), enableSorting: false,
      cell: ({ row }) => subAccount(row.original.subAccount) },
    { id: 'source', header: t('wallet.feeAccount.source'), enableSorting: false,
      cell: ({ row }) => sourceOf(row.original.subAccount, row.original.source) },
    { id: 'amount', header: t('wallet.feeAccount.amount'), enableSorting: false, meta: { card: 'badge', className: 'text-right' },
      cell: ({ row }) => formatKudos(row.original.amount) },
  ], [t, subAccount, sourceOf])
  const cash = wallet.cashBalance
  return (
    <section aria-labelledby="wallet-fee-account" className="flex flex-col gap-3" data-testid="wallet-fee-account">
      <h2 id="wallet-fee-account" className="text-lg font-semibold">{t('wallet.feeAccount.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('wallet.feeAccount.intro')}</p>
      {wallet.autoPay
        ? <p className="text-sm" data-testid="fee-account-auto-pay">{t('wallet.feeAccount.autoPay')}</p> : null}
      <DescriptionList columns={3} items={[
        { key: 'total', label: t('wallet.feeAccount.total'),
          value: <span data-testid="fee-account-total">{formatKudos(wallet.feeCreditBalance + cash)}</span> },
        { key: 'credit', label: t('wallet.feeAccount.credit'),
          value: <span data-testid="fee-account-credit">{formatKudos(wallet.feeCreditBalance)}</span> },
        { key: 'cash', label: t('wallet.feeAccount.cash'),
          value: <span data-testid="fee-account-cash">{formatKudos(cash)}</span> },
        // F-M5: what the prepayments of your publications still hold.
        ...(wallet.prepaymentHeld > 0
          ? [{ key: 'prepaymentHeld', label: t('wallet.feeAccount.prepaymentHeld'),
            value: <span data-testid="fee-account-prepayment-held">{formatKudos(wallet.prepaymentHeld)}</span> }] : []),
        // F-M7: the credit of credit notes not used yet; the next invoices take it first.
        ...(wallet.creditNoteCredit > 0
          ? [{ key: 'creditNoteCredit', label: t('wallet.feeAccount.creditNoteCredit'),
            value: <span data-testid="fee-account-credit-note">{formatKudos(wallet.creditNoteCredit)}</span> }] : []),
      ]} />
      <h3 className="font-semibold">{t('wallet.feeAccount.lots')}</h3>
      {lots.isError
        ? <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p>
        : (
          <DataTable label={t('wallet.feeAccount.lotsTable')} columns={lotColumns} data={lots.data?.items ?? []}
            storageKey="qb.sponsor.fee-account-lots" getRowId={(row) => row.lotId} loading={lots.isPending}
            stale={lots.isPlaceholderData} empty={t('wallet.feeAccount.noLots')} pagination={lotPage}
            onPaginationChange={setLotPage} rowCount={lots.data?.total}
            rowProps={(row) => ({ 'data-testid': 'fee-lot-row', 'data-sub-account': row.subAccount })} />
        )}
      <h3 className="font-semibold">{t('wallet.feeAccount.payments')}</h3>
      {payments.isError
        ? <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p>
        : (
          <DataTable label={t('wallet.feeAccount.paymentsTable')} columns={paymentColumns}
            data={payments.data?.items ?? []} storageKey="qb.sponsor.fee-payments" getRowId={(row) => row.paymentId}
            loading={payments.isPending} stale={payments.isPlaceholderData} empty={t('wallet.feeAccount.noPayments')}
            pagination={paymentPage} onPaginationChange={setPaymentPage} rowCount={payments.data?.total}
            rowProps={(row) => ({ 'data-testid': 'fee-payment-row', 'data-sub-account': row.subAccount })} />
        )}
    </section>
  )
}
