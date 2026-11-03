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
import { FEES_KEY, readFeeInvoice, readFeeRequest, REQUEST_REVIEW } from './fee-api'
import { money, useLabels, when } from './hooks'
import { DICTIONARY, isUserId, PATH, PERMISSION } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/**
 * One fee request (Q87): a fee by hand or an invoice voided, whose, how much and why, and — while it waits — the
 * platform's `ApprovalPanel`. The approval is bound to what is recorded here; the one who recorded it cannot approve
 * it (the platform refuses). Approving charges or voids on the server; the page then reads the request again.
 */
export default function FeeRequestPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { requestId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const status = useLabels(DICTIONARY.feeRequestStatus)
  const kind = useLabels(DICTIONARY.feeRequestKind)
  const invoiceStatus = useLabels(DICTIONARY.feeInvoiceStatus)
  const [decided, setDecided] = useState<number | null>(null)
  const known = isUserId(requestId)
  const request = useQuery({
    queryKey: [...FEES_KEY, 'request', requestId],
    queryFn: () => readFeeRequest(requestId),
    enabled: known,
    // After a decision, until the request has left the review (the approval's event decides it shortly after), for a
    // minute at most: the page then says the outcome follows, and a reload reads it again.
    refetchInterval: (query) => (decided !== null && Date.now() - decided < 60_000
      && query.state.data?.status === REQUEST_REVIEW ? 2000 : false),
    refetchOnWindowFocus: false,
  })
  const invoiceId = request.data?.invoiceId ?? null
  const invoice = useQuery({
    queryKey: [...FEES_KEY, 'invoice', invoiceId],
    queryFn: () => readFeeInvoice(invoiceId!),
    enabled: !!invoiceId,
  })

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.feesWhere({ tab: 'requests' })}><ArrowLeft aria-hidden />{t('fee.detail.back')}</Link>
    </Button>
  )
  if (known && request.isPending) return <PageState kind="loading" />
  if (request.isError) return <LoadFailed error={request.error} onRetry={() => void request.refetch()} />
  if (!known || !request.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('fee.detail.notFound')} /></div>
  }
  const fr = request.data
  const pending = fr.status === REQUEST_REVIEW && !!fr.approvalRequestId

  let decision: ReactNode
  if (decided !== null && pending) {
    decision = <p role="status" data-testid="fee-decided">{t('fee.detail.decided')}</p>
  } else if (!pending) {
    decision = <p data-testid="fee-not-pending">{t('fee.detail.notPending', { status: status(fr.status) })}</p>
  } else if (!can(PERMISSION.bankApprove)) {
    decision = <p className="text-muted-foreground text-sm">{t('fee.detail.notAllowed')}</p>
  } else {
    decision = (
      <>
        <p className="text-muted-foreground text-sm">{t('fee.detail.decisionHint')}</p>
        <ApprovalPanel requestId={fr.approvalRequestId!} onDecided={() => {
          setDecided(Date.now())
          void request.refetch()
          void queryClient.invalidateQueries({ queryKey: FEES_KEY })
        }} />
      </>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid="fee-request" data-status={fr.status}>
      <div>{back}</div>
      <PageHeader title={`${t('fee.detail.title')}: ${kind(fr.kind)}`} className="pb-0"
        description={
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span>{t('fee.sponsor')}: <Link to={PATH.user(fr.sponsorId)} className={linkClass}>
              {fr.company ?? <UserName id={fr.sponsorId} />}</Link></span>
            <StatusBadge code={fr.status} label={status(fr.status)} testId="fee-request-status" />
          </span>
        } />
      <Section title={t('fee.detail.facts')} testId="fee-facts">
        <DescriptionList columns={3} items={[
          { key: 'kind', label: t('fee.kind'), value: <span data-testid="fee-kind">{kind(fr.kind)}</span> },
          ...(fr.amount ? [{ key: 'amount', label: t('fee.amount'),
            value: <span className="font-semibold" data-testid="fee-amount">{money(fr.amount)}</span> }] : []),
          ...(fr.invoiceId ? [{ key: 'invoice', label: t('fee.invoice'),
            value: (
              <span className="flex flex-wrap items-center gap-2" data-testid="fee-invoice">
                <code className="font-mono">{invoice.data?.invoiceNo ?? fr.invoiceId}</code>
                {invoice.data && <StatusBadge code={invoice.data.status} label={invoiceStatus(invoice.data.status)} />}
                {invoice.data && <span>{money(invoice.data.total)}</span>}
              </span>
            ) }] : []),
          { key: 'reason', label: t('fee.reason'), value: <span data-testid="fee-reason">{fr.reason}</span> },
          { key: 'requestedBy', label: t('fee.requestedBy'),
            value: fr.requestedBy ? <UserName id={fr.requestedBy} /> : '' },
          { key: 'requestedAt', label: t('fee.requestedAt'), value: when(fr.requestedAt) },
          ...(fr.decidedAt ? [{ key: 'decidedAt', label: t('fee.decidedAt'), value: when(fr.decidedAt) }] : []),
          ...(fr.chargeId ? [{ key: 'charge', label: t('fee.detail.charge'),
            value: <code className="font-mono">{fr.chargeId}</code> }] : []),
        ]} />
      </Section>
      <Section title={t('fee.detail.decision')} testId="fee-decision">{decision}</Section>
    </div>
  )
}
