import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, AlertDescription, ApiError, Button, DataTable, DescriptionList, EXTENSION_NAMESPACE, notify, PageState,
  useAuth, type ColumnDef } from '@jabiz/admin'
import { EmptyState, LoadFailed, Section } from './components'
import { confirmOnboarding, readFeeAccount, readFeeAccountLots, readFeePayments, readOnboarding, type FeeAccountLot,
  type FeePaymentPart } from './fee-api'
import { money, useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PERMISSION } from './ids'

const right = { className: 'text-right tabular-nums' }

/**
 * F-M2: the Sponsor's onboarding platform fee Kudos, on the operator's page. A lead of the Sponsor's operator confirms
 * that the Sponsor's details are complete, which grants them once; the server refuses while the grant is switched off,
 * for a Sponsor not approved or banned, once given, and for a member who is not a lead (its words are shown).
 */
function OnboardingGrant({ userId }: { userId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  const key = ['qb-ext', 'fee-onboarding', userId]
  const onboarding = useQuery({ queryKey: key, queryFn: () => readOnboarding(userId) })
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  if (onboarding.isError) return <LoadFailed error={onboarding.error} onRetry={() => void onboarding.refetch()} />
  if (onboarding.isPending || !onboarding.data) return null
  const { enabled, grantedAt, amount, expiresAt } = onboarding.data
  const confirm = async () => {
    setBusy(true)
    setProblem(null)
    try {
      await confirmOnboarding(userId)
      notify.success(t('fee.onboarding.done'))
      void queryClient.invalidateQueries({ queryKey: ['qb-ext'] })
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="flex flex-col gap-2" data-testid="sponsor-fee-onboarding">
      <h3 className="font-semibold">{t('fee.onboarding.title')}</h3>
      {grantedAt ? (
        <p className="text-sm" data-testid="fee-onboarding-granted">
          {t('fee.onboarding.granted', { amount: money(amount ?? '0'), at: when(grantedAt), until: when(expiresAt) })}
        </p>
      ) : (
        <>
          <p className="text-muted-foreground text-sm">{t(enabled ? 'fee.onboarding.hint' : 'fee.onboarding.off')}</p>
          <span>
            <Button size="sm" disabled={!enabled || busy} aria-busy={busy || undefined} onClick={() => void confirm()}
              data-testid="fee-onboarding-confirm">
              {t('fee.onboarding.confirm')}
            </Button>
          </span>
        </>
      )}
      {problem && (
        <Alert variant="destructive" data-testid="fee-onboarding-problem"><AlertDescription>{problem}</AlertDescription></Alert>
      )}
    </div>
  )
}

/**
 * One Sponsor's account that pays the platform's fees (F-M1), read only: the granted credit and the cash, the lots with
 * money left in the order they are spent (the credit first, the earliest to expire first, then the cash as it arrived)
 * and how each payment was split. Finance reads any Sponsor's; an operator its own Sponsors' only (`operator`: the
 * operator's templates, which give nothing for another operator's Sponsor). The page shows what the server gives.
 */
export function SponsorFeeAccount({ userId, operator }: { userId: string; operator: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
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
      {operator && can(PERMISSION.feeCreditOnboarding) && <OnboardingGrant userId={userId} />}
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
