import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApprovalPanel,
  Badge,
  Button,
  DescriptionList,
  EXTENSION_NAMESPACE,
  PageHeader,
  PageState,
  UserName,
  useAuth,
} from '@jabiz/admin'
import { readBankTopUp } from './finance-api'
import { LoadFailed, Section } from './components'
import { money, useLabels, when } from './hooks'
import { BANK_REVIEW, DICTIONARY, isUserId, PATH, PERMISSION } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/**
 * One bank top-up (Q13a): what the sponsor asked for and reported, what finance recorded from the statement, and —
 * while it waits for approval — the platform's `ApprovalPanel`. The approval is bound to what is recorded here (who,
 * how much, which statement entry); the one who recorded it cannot approve it (the platform refuses). Approving
 * credits the sponsor's wallet with the amount received, on the server; the page then reads the top-up again.
 */
export default function BankTopUpPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { topUpId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const status = useLabels(DICTIONARY.topUpStatus)
  const [decided, setDecided] = useState<number | null>(null)
  const known = isUserId(topUpId)
  const topUp = useQuery({
    queryKey: ['qb-ext', 'finance', 'bank-topups', 'one', topUpId],
    queryFn: () => readBankTopUp(topUpId),
    enabled: known,
    // After a decision, until the top-up has left the review (the approval's event credits it shortly after), for a
    // minute at most: the page then says the state follows, and a reload reads it again.
    refetchInterval: (query) => (decided !== null && Date.now() - decided < 60_000
      && query.state.data?.status === BANK_REVIEW ? 2000 : false),
    refetchOnWindowFocus: false,
  })

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.bankTopUps}><ArrowLeft aria-hidden />{t('bank.detail.back')}</Link>
    </Button>
  )
  if (known && topUp.isPending) return <PageState kind="loading" />
  if (topUp.isError) return <LoadFailed error={topUp.error} onRetry={() => void topUp.refetch()} />
  if (!known || !topUp.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('bank.detail.notFound')} /></div>
  }
  const tu = topUp.data
  const pending = tu.status === BANK_REVIEW && !!tu.approvalRequestId
  const recorded = tu.receivedOn !== null
  const differs = recorded && tu.amount !== tu.requestedAmount

  let decision: ReactNode
  if (decided !== null && pending) {
    decision = <p role="status" data-testid="bank-decided">{t('bank.detail.decided')}</p>
  } else if (!pending) {
    decision = <p data-testid="bank-not-pending">{t('bank.detail.notPending', { status: status(tu.status) })}</p>
  } else if (!can(PERMISSION.bankApprove)) {
    decision = <p className="text-muted-foreground text-sm">{t('bank.detail.notAllowed')}</p>
  } else {
    decision = (
      <>
        <p className="text-muted-foreground text-sm">{t('bank.detail.decisionHint')}</p>
        <ApprovalPanel requestId={tu.approvalRequestId!} onDecided={() => {
          setDecided(Date.now())
          void topUp.refetch()
          void queryClient.invalidateQueries({ queryKey: ['qb-ext', 'finance', 'bank-topups'] })
        }} />
      </>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid="bank-topup" data-status={tu.status}>
      <div>{back}</div>
      <PageHeader title={t('bank.detail.title', { code: tu.transferCode ?? '' })} className="pb-0"
        description={
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span>{t('bank.sponsor')}: <Link to={PATH.user(tu.sponsorId)} className={linkClass}>
              {tu.company ?? <UserName id={tu.sponsorId} />}</Link></span>
            <Badge variant={pending ? 'secondary' : 'outline'} data-testid="bank-status">{status(tu.status)}</Badge>
          </span>
        } />
      <Section title={t('bank.detail.facts')} testId="bank-facts">
        <DescriptionList columns={3} items={[
          { key: 'code', label: t('bank.code'), value: <code className="font-mono">{tu.transferCode}</code> },
          { key: 'requested', label: t('bank.requested'), value: money(tu.requestedAmount) },
          { key: 'createdAt', label: t('bank.createdAt'), value: when(tu.createdAt) },
          { key: 'payer', label: t('bank.payer'), value: tu.payerName
            ? t('bank.payerValue', { name: tu.payerName, date: tu.transferDate ?? '' }) : t('bank.notReported') },
          ...(tu.reportedAt ? [{ key: 'reportedAt', label: t('bank.reportedAt'), value: when(tu.reportedAt) }]
            : []),
        ]} />
      </Section>
      {recorded && (
        <Section title={t('bank.detail.record')} testId="bank-record">
          <DescriptionList columns={3} items={[
            { key: 'amount', label: t('bank.detail.amount'),
              value: <span className="font-semibold" data-testid="bank-received">{money(tu.amount)}</span> },
            { key: 'receivedOn', label: t('bank.detail.receivedOn'), value: tu.receivedOn },
            { key: 'reference', label: t('bank.detail.reference'),
              value: <code className="font-mono" data-testid="bank-reference">{tu.bankReference}</code> },
            { key: 'recordedBy', label: t('bank.detail.recordedBy'),
              value: tu.recordedBy ? <UserName id={tu.recordedBy} /> : '' },
            { key: 'recordedAt', label: t('bank.detail.recordedAt'), value: when(tu.recordedAt) },
            ...(tu.paidAt ? [{ key: 'paidAt', label: t('bank.detail.paidAt'), value: when(tu.paidAt) }] : []),
          ]} />
          {differs && (
            <Alert variant="warning" data-testid="bank-differs">
              <AlertDescription>{t('bank.detail.differs', { requested: money(tu.requestedAmount) })}</AlertDescription>
            </Alert>
          )}
        </Section>
      )}
      <Section title={t('bank.detail.decision')} testId="bank-decision">{decision}</Section>
    </div>
  )
}
