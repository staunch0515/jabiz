import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { DataTable, DescriptionList, EXTENSION_NAMESPACE, PageState, type ColumnDef } from '@jabiz/admin'
import { EmptyState, LoadFailed, Section } from './components'
import { readFeeAccount, readFeeAccountLots, readFeePayments, type FeeAccountLot, type FeePaymentPart } from './fee-api'
import { money, useLabels, usePaged, when } from './hooks'
import { DICTIONARY } from './ids'

const right = { className: 'text-right tabular-nums' }

/**
 * One Sponsor's account that pays the platform's fees (F-M1), read only: the granted credit and the cash, the lots with
 * money left in the order they are spent (the credit first, the earliest to expire first, then the cash as it arrived)
 * and how each payment was split. Finance reads any Sponsor's; an operator its own Sponsors' only (`operator`: the
 * operator's templates, which give nothing for another operator's Sponsor). The page shows what the server gives.
 */
export function SponsorFeeAccount({ userId, operator }: { userId: string; operator: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const subAccount = useLabels(DICTIONARY.feeSubAccount)
  const creditSource = useLabels(DICTIONARY.feeCreditSource)
  const cashSource = useLabels(DICTIONARY.cashSource)
  const sourceOf = useMemo(() => (sub: string, source: string | null) => (sub === 'FEE_CREDIT'
    ? creditSource(source) : cashSource(source)), [creditSource, cashSource])
  const account = useQuery({ queryKey: ['qb-ext', 'fee-account', userId, operator],
    queryFn: () => readFeeAccount(userId, operator) })
  const lots = usePaged(['qb-ext', 'fee-account-lots', userId, operator],
    (offset, limit) => readFeeAccountLots(userId, operator, offset, limit))
  const payments = usePaged(['qb-ext', 'fee-payments', userId, operator],
    (offset, limit) => readFeePayments(userId, operator, offset, limit))
  const lotColumns = useMemo<ColumnDef<FeeAccountLot, unknown>[]>(() => [
    { id: 'seq', header: t('fee.account.order'), meta: right, cell: ({ row }) => row.original.seq },
    { id: 'subAccount', header: t('fee.account.subAccount'), cell: ({ row }) => subAccount(row.original.subAccount) },
    { id: 'source', header: t('fee.account.source'),
      cell: ({ row }) => sourceOf(row.original.subAccount, row.original.source) },
    { id: 'arrivedAt', header: t('fee.account.arrivedAt'), cell: ({ row }) => when(row.original.arrivedAt) },
    { id: 'expiresAt', header: t('fee.account.expiresAt'), cell: ({ row }) => when(row.original.expiresAt) },
    { id: 'amount', header: t('fee.account.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'remaining', header: t('fee.account.remaining'), meta: right,
      cell: ({ row }) => money(row.original.remaining) },
  ], [t, subAccount, sourceOf])
  const paymentColumns = useMemo<ColumnDef<FeePaymentPart, unknown>[]>(() => [
    { id: 'paidAt', header: t('fee.account.paidAt'), cell: ({ row }) => when(row.original.paidAt) },
    { id: 'invoiceNo', header: t('fee.account.invoice'), cell: ({ row }) => row.original.invoiceNo ?? '' },
    { id: 'subAccount', header: t('fee.account.subAccount'), cell: ({ row }) => subAccount(row.original.subAccount) },
    { id: 'source', header: t('fee.account.source'),
      cell: ({ row }) => sourceOf(row.original.subAccount, row.original.source) },
    { id: 'amount', header: t('fee.account.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
  ], [t, subAccount, sourceOf])

  let totals
  if (account.isError) totals = <LoadFailed error={account.error} onRetry={() => void account.refetch()} />
  else if (account.isPending) totals = <PageState kind="loading" />
  else {
    totals = (
      <DescriptionList columns={3} items={[
        { key: 'credit', label: t('fee.account.credit'), value: money(account.data.feeCreditBalance) },
        { key: 'cash', label: t('fee.account.cash'), value: money(account.data.cashBalance) },
        { key: 'held', label: t('fee.account.held'), value: money(account.data.cashHeld) },
      ]} />
    )
  }
  return (
    <Section title={t('fee.account.title')} testId="sponsor-fee-account">
      <p className="text-muted-foreground text-sm">{t('fee.account.hint')}</p>
      {totals}
      <div className="flex flex-col gap-2" data-testid="sponsor-fee-lots">
        <h3 className="font-semibold">{t('fee.account.lots')}</h3>
        {lots.query.isError
          ? <LoadFailed error={lots.query.error} onRetry={() => void lots.query.refetch()} />
          : (
            <DataTable label={t('fee.account.lots')} columns={lotColumns} {...lots.table}
              getRowId={(row) => row.lotId} empty={<EmptyState art="money" title={t('fee.account.noLots')} />}
              rowProps={(row) => ({ 'data-testid': 'fee-lot-row', 'data-sub-account': row.subAccount })} />
          )}
      </div>
      <div className="flex flex-col gap-2" data-testid="sponsor-fee-payments">
        <h3 className="font-semibold">{t('fee.account.payments')}</h3>
        {payments.query.isError
          ? <LoadFailed error={payments.query.error} onRetry={() => void payments.query.refetch()} />
          : (
            <DataTable label={t('fee.account.payments')} columns={paymentColumns} {...payments.table}
              getRowId={(row) => row.paymentId}
              empty={<EmptyState art="money" title={t('fee.account.noPayments')} />}
              rowProps={() => ({ 'data-testid': 'fee-payment-row' })} />
          )}
      </div>
    </Section>
  )
}
