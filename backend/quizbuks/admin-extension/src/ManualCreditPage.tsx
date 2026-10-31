import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  ApprovalPanel,
  Button,
  DescriptionList,
  EXTENSION_NAMESPACE,
  PageHeader,
  PageState,
  UserName,
  useAuth,
} from '@jabiz/admin'
import { LoadFailed, Section, StatusBadge } from './components'
import { money, useLabels, when } from './hooks'
import { BANK_REVIEW, DICTIONARY, isUserId, PATH, PERMISSION } from './ids'
import { readManualCredit } from './manual-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const KEY = ['qb-ext', 'finance', 'manual-credits'] as const

/**
 * One manual credit (Q18b): whose wallet, how much, which kind and why, and — while it waits for approval — the
 * platform's `ApprovalPanel`. The approval is bound to what is recorded here; the one who recorded it cannot approve
 * it (the platform refuses). Approving credits the sponsor's wallet, on the server; the page then reads it again.
 */
export default function ManualCreditPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { topUpId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const status = useLabels(DICTIONARY.topUpStatus)
  const [decided, setDecided] = useState<number | null>(null)
  const known = isUserId(topUpId)
  const credit = useQuery({
    queryKey: [...KEY, 'one', topUpId],
    queryFn: () => readManualCredit(topUpId),
    enabled: known,
    // After a decision, until the credit has left the review (the approval's event credits it shortly after), for a
    // minute at most: the page then says the state follows, and a reload reads it again.
    refetchInterval: (query) => (decided !== null && Date.now() - decided < 60_000
      && query.state.data?.status === BANK_REVIEW ? 2000 : false),
    refetchOnWindowFocus: false,
  })

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.manualCredits}><ArrowLeft aria-hidden />{t('manual.detail.back')}</Link>
    </Button>
  )
  if (known && credit.isPending) return <PageState kind="loading" />
  if (credit.isError) return <LoadFailed error={credit.error} onRetry={() => void credit.refetch()} />
  if (!known || !credit.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('manual.detail.notFound')} /></div>
  }
  const mc = credit.data
  const pending = mc.status === BANK_REVIEW && !!mc.approvalRequestId
  const outside = mc.manualKind === 'PAID_OUTSIDE'

  let decision: ReactNode
  if (decided !== null && pending) {
    decision = <p role="status" data-testid="manual-decided">{t('manual.detail.decided')}</p>
  } else if (!pending) {
    decision = <p data-testid="manual-not-pending">{t('manual.detail.notPending', { status: status(mc.status) })}</p>
  } else if (!can(PERMISSION.bankApprove)) {
    decision = <p className="text-muted-foreground text-sm">{t('manual.detail.notAllowed')}</p>
  } else {
    decision = (
      <>
        <p className="text-muted-foreground text-sm">{t('manual.detail.decisionHint')}</p>
        <ApprovalPanel requestId={mc.approvalRequestId!} onDecided={() => {
          setDecided(Date.now())
          void credit.refetch()
          void queryClient.invalidateQueries({ queryKey: KEY })
        }} />
      </>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid="manual-credit" data-status={mc.status}>
      <div>{back}</div>
      <PageHeader title={t('manual.detail.title', { code: mc.transferCode ?? '' })} className="pb-0"
        description={
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span>{t('manual.sponsor')}: <Link to={PATH.user(mc.sponsorId)} className={linkClass}>
              {mc.company ?? <UserName id={mc.sponsorId} />}</Link></span>
            <StatusBadge code={mc.status} label={status(mc.status)} testId="manual-status" />
          </span>
        } />
      <Section title={t('manual.detail.facts')} testId="manual-facts">
        <DescriptionList columns={3} items={[
          { key: 'code', label: t('manual.code'), value: <code className="font-mono">{mc.transferCode}</code> },
          { key: 'kind', label: t('manual.kind'),
            value: <span data-testid="manual-kind">{t(`manual.kinds.${mc.manualKind}`)}</span> },
          { key: 'amount', label: t('manual.amount'),
            value: <span className="font-semibold" data-testid="manual-amount">{money(mc.amount)}</span> },
          { key: 'reason', label: t('manual.reason'), value: <span data-testid="manual-reason">{mc.manualReason}</span> },
          ...(outside ? [
            { key: 'receivedOn', label: t('manual.detail.receivedOn'), value: mc.receivedOn ?? '' },
            { key: 'reference', label: t('manual.detail.reference'),
              value: <code className="font-mono" data-testid="manual-reference">{mc.bankReference}</code> },
          ] : []),
          { key: 'recordedBy', label: t('manual.recordedBy'),
            value: mc.recordedBy ? <UserName id={mc.recordedBy} /> : '' },
          { key: 'createdAt', label: t('manual.createdAt'), value: when(mc.createdAt) },
          ...(mc.paidAt ? [{ key: 'paidAt', label: t('manual.detail.paidAt'), value: when(mc.paidAt) }] : []),
        ]} />
      </Section>
      <Section title={t('manual.detail.decision')} testId="manual-decision">{decision}</Section>
    </div>
  )
}
