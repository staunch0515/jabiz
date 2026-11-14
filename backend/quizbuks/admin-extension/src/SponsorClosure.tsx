import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { DescriptionList, EXTENSION_NAMESPACE, PageState } from '@jabiz/admin'
import { minuteNow, readMoneyClosure, readMoneyClosureAmounts } from './closure-api'
import { LoadFailed, Section, StatusBadge } from './components'
import { money, useLabels, when } from './hooks'
import { DICTIONARY } from './ids'

/**
 * F-M15 b: the wind-up of an erased Sponsor's platform fee account, on the Sponsor's page (finance, read only). Nothing
 * shows for a Sponsor who has none. The amounts are the server's sums; the page adds nothing up.
 */
export function SponsorClosure({ userId }: { userId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const status = useLabels(DICTIONARY.sponsorClosureStatus)
  const reason = useLabels(DICTIONARY.sponsorClosureReason)
  const closure = useQuery({ queryKey: ['qb-ext', 'money-closure', userId],
    queryFn: () => readMoneyClosure(userId, minuteNow()) })
  const amounts = useQuery({ queryKey: ['qb-ext', 'money-closure-amounts', userId],
    queryFn: () => readMoneyClosureAmounts(userId), enabled: closure.data != null })
  if (closure.isError) return <LoadFailed error={closure.error} onRetry={() => void closure.refetch()} />
  if (closure.isPending || closure.data == null) return null
  const row = closure.data
  let sums
  if (amounts.isError) sums = <LoadFailed error={amounts.error} onRetry={() => void amounts.refetch()} />
  else if (amounts.isPending) sums = <PageState kind="loading" />
  else if (amounts.data) {
    const a = amounts.data
    sums = (
      <DescriptionList columns={3} items={[
        { key: 'paidFromCredit', label: t('closures.block.paidFromCredit'), value: money(a.paidFromCredit) },
        { key: 'paidFromCash', label: t('closures.block.paidFromCash'), value: money(a.paidFromCash) },
        { key: 'creditVoided', label: t('closures.block.creditVoided'), value: money(a.creditVoided) },
        { key: 'owed', label: t('closures.block.owed'), value: money(a.owed) },
        { key: 'cashLeft', label: t('closures.block.cashLeft'), value: money(a.cashLeft) },
      ]} />
    )
  }
  return (
    <Section title={t('closures.block.title')} testId="sponsor-closure">
      <p className="text-muted-foreground text-sm">{t('closures.block.hint')}</p>
      <DescriptionList columns={3} items={[
        { key: 'status', label: t('closures.block.status'), value: (
          <span className="flex items-center gap-2">
            <StatusBadge code={row.status} label={status(row.status)} testId="sponsor-closure-status" />
            {row.slow && <StatusBadge code="SLOW" label={t('closures.slow')} testId="sponsor-closure-slow" />}
          </span>) },
        { key: 'reason', label: t('closures.block.reason'),
          value: row.reasonCode ? reason(row.reasonCode) : t('closures.block.noReason') },
        { key: 'erased', label: t('closures.block.erased'), value: when(row.erasedAt) },
        { key: 'lastRun', label: t('closures.block.lastRun'), value: when(row.lastRunAt) },
        { key: 'closed', label: t('closures.block.closed'), value: when(row.closedAt) },
      ]} />
      {sums}
    </Section>
  )
}
