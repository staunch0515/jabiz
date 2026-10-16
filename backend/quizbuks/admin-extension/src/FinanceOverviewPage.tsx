import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  DescriptionList,
  EXTENSION_NAMESPACE,
  PageHeader,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  useAuth,
  type DescriptionItem,
} from '@jabiz/admin'
import { readAccountBalances, readFinanceSummary, readReconSummary } from './finance-api'
import { LoadFailed, Section } from './components'
import { money, when } from './hooks'
import { ACCOUNTS, PATH, PERMISSION } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/** A count that leads to the page listing what it counts. */
function CountLink({ to, count, label, testId }: { to: string; count: number; label: string; testId: string }) {
  return (
    <Link to={to} className={linkClass} data-testid={testId} data-count={count} aria-label={label}>
      {count.toLocaleString()}
    </Link>
  )
}

/** The ledger's accounts now (the platform's account balances; `ledger.read`). */
function Accounts() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const balances = useQuery({ queryKey: ['qb-ext', 'finance', 'accounts'], queryFn: readAccountBalances })
  let body: ReactNode
  if (balances.isError) body = <LoadFailed error={balances.error} onRetry={() => void balances.refetch()} />
  else if (balances.isPending) body = <PageState kind="loading" />
  else {
    const byCode = new Map(balances.data.map((account) => [account.code, account]))
    body = (
      <Table aria-label={t('finance.overview.accountsTable')} data-testid="finance-accounts">
        <TableHeader>
          <TableRow>
            <TableHead>{t('finance.overview.account')}</TableHead>
            <TableHead className="text-right">{t('finance.overview.balance')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {ACCOUNTS.map((code) => (
            <TableRow key={code} data-testid="finance-account" data-code={code}>
              <TableCell>{`${code} ${byCode.get(code)?.name ?? ''}`.trim()}</TableCell>
              <TableCell className="text-right tabular-nums">{money(byCode.get(code)?.balance ?? '0')}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    )
  }
  return (
    <Section title={t('finance.overview.accounts')} testId="finance-accounts-section">
      <p className="text-muted-foreground text-sm">{t('finance.overview.accountsHint')}</p>
      {body}
    </Section>
  )
}

/**
 * Finance's overview (Q9c): the ledger's accounts now, the wallets' totals and what waits for finance today, each
 * number leading to the page that lists it: transfers waiting for review, in doubt or holding a taker, the latest
 * reconciliation and its differences, and the money tasks in the platform's task list.
 */
export default function FinanceOverviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const summary = useQuery({ queryKey: ['qb-ext', 'finance', 'summary'], queryFn: readFinanceSummary })
  // The reconciliation and the money tasks are qb.reconcile's (and the tasks task.read's): asked for only then.
  const reconciles = can(PERMISSION.reconcile) && can(PERMISSION.taskRead)
  const recon = useQuery({ queryKey: ['qb-ext', 'finance', 'recon-summary'], queryFn: readReconSummary,
    enabled: reconciles })
  let reconItems: DescriptionItem[] = []
  if (reconciles && recon.isError) {
    reconItems = [{ key: 'recon', label: t('finance.overview.lastRun'),
      value: <LoadFailed error={recon.error} onRetry={() => void recon.refetch()} /> }]
  } else if (reconciles && recon.data) {
    const r = recon.data
    reconItems = [
      { key: 'recon', label: t('finance.overview.lastRun'), value: r.lastRunId
        ? (
          <Link to={PATH.reconRun(r.lastRunId)} className={linkClass} data-testid="finance-last-run"
            data-status={r.lastRunStatus ?? ''}>
            {t('finance.overview.lastRunValue', { time: when(r.lastRunAt),
              status: t(`finance.recon.status.${r.lastRunStatus === 'DIFF' ? 'DIFF' : 'OK'}`),
              n: r.lastRunItems })}
          </Link>
        )
        : t('finance.overview.noRun') },
      { key: 'tasks', label: t('finance.overview.openTasks'), value: (
        <CountLink to={PATH.tasks} count={r.openTasks}
          label={t('finance.overview.openTasksLink', { n: r.openTasks })} testId="finance-tasks" />) },
    ]
  }
  let numbers: ReactNode
  if (summary.isError) numbers = <LoadFailed error={summary.error} onRetry={() => void summary.refetch()} />
  else if (summary.isPending) numbers = <PageState kind="loading" />
  else {
    const s = summary.data
    numbers = (
      <>
        <Section title={t('finance.overview.waiting')} testId="finance-waiting">
          <DescriptionList columns={3} items={[
            { key: 'review', label: t('finance.overview.transfersInReview'), value: (
              <CountLink to={PATH.transfersWhere({ status: 'REVIEW' })} count={s.transfersInReview}
                label={t('finance.overview.transfersInReviewLink', { n: s.transfersInReview })}
                testId="finance-in-review" />) },
            { key: 'doubt', label: t('finance.overview.transfersInDoubt'), value: (
              <CountLink to={PATH.transfersWhere({ stale: '1' })} count={s.transfersInDoubt}
                label={t('finance.overview.transfersInDoubtLink', { n: s.transfersInDoubt })}
                testId="finance-in-doubt" />) },
            { key: 'held', label: t('finance.overview.heldTakers'), value: (
              <CountLink to={PATH.transfersWhere({ held: '1' })} count={s.heldTakers}
                label={t('finance.overview.heldTakersLink', { n: s.heldTakers })} testId="finance-held" />) },
            ...reconItems,
          ]} />
        </Section>
        <Section title={t('finance.overview.wallets')} testId="finance-wallets">
          <DescriptionList columns={3} items={[
            { key: 'sponsorBalance', label: t('finance.overview.sponsorBalance'), value: money(s.sponsorBalance) },
            { key: 'sponsorHeld', label: t('finance.overview.sponsorHeld'), value: money(s.sponsorHeld) },
            { key: 'pending', label: t('finance.overview.sponsorPending'),
              value: t('finance.overview.sponsorPendingValue', { n: s.sponsorPendingCount.toLocaleString(),
                amount: money(s.sponsorPendingAmount) }) },
            { key: 'takerBalance', label: t('finance.overview.takerBalance'), value: money(s.takerBalance) },
            { key: 'takerOut', label: t('finance.overview.takerPendingOut'), value: money(s.takerPendingOut) },
          ]} />
        </Section>
      </>
    )
  }
  return (
    <div className="flex flex-col gap-4" data-testid="finance-overview">
      <PageHeader title={t('finance.overview.title')} description={t('finance.overview.description')}
        className="pb-0" />
      <nav aria-label={t('finance.nav')} className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
        <Link to={PATH.transfers} className={linkClass}>{t('menu.transfers')}</Link>
        <Link to={PATH.topUps} className={linkClass}>{t('menu.topUps')}</Link>
        {can(PERMISSION.reconcile) && <Link to={PATH.reconciliation} className={linkClass}>{t('menu.reconciliation')}</Link>}
        <Link to={PATH.reports} className={linkClass}>{t('menu.reports')}</Link>
      </nav>
      {numbers}
      {can(PERMISSION.ledgerRead) && <Accounts />}
    </div>
  )
}
