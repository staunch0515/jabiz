import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
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
  FilePreview,
  MarkdownView,
  PageHeader,
  PageState,
  useAuth,
  type DescriptionItem,
} from '@jabiz/admin'
import { readSponsor, readUser } from './api'
import { AVATAR_VARIANTS, DICTIONARY, PATH, PERMISSION, SPONSOR_PENDING } from './ids'
import { LoadFailed, Section } from './components'
import { QUEUE, useAfterDecision, useLabels, when } from './hooks'

const AVATAR = { image: true, variants: AVATAR_VARIANTS }
const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/**
 * A sponsor's application as the reviewer decides it (P-09): the contact name, company, introduction and picture the
 * approval is bound to, when the terms were accepted, and the account's address and whether it is verified. The
 * decision is the platform's `ApprovalPanel`, offered while the application waits; afterwards the page reads again
 * until the state follows it.
 */
export default function SponsorReviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { userId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const after = useAfterDecision()
  const statusLabel = useLabels(DICTIONARY.sponsorReviewStatus)
  const sponsor = useQuery({
    queryKey: ['qb-ext', 'sponsor', userId],
    queryFn: () => readSponsor(userId),
    refetchInterval: (query) => after.interval(query.state.data?.reviewStatus === SPONSOR_PENDING),
  })
  const user = useQuery({ queryKey: ['qb-ext', 'user', userId], queryFn: () => readUser(userId) })

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={`${PATH.reviews}?tab=sponsors`}><ArrowLeft aria-hidden />{t('sponsor.back')}</Link>
    </Button>
  )
  if (sponsor.isPending) return <PageState kind="loading" />
  if (sponsor.isError) return <LoadFailed error={sponsor.error} onRetry={() => void sponsor.refetch()} />
  if (!sponsor.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('sponsor.notFound')} /></div>
  }
  const s = sponsor.data
  const pending = s.reviewStatus === SPONSOR_PENDING && !!s.approvalRequestId
  const notSet = t('common.notSet')
  const email = user.data?.email
    ? `${user.data.email} (${user.data.emailVerified ? t('sponsor.verified') : t('sponsor.notVerified')})`
    : notSet
  const items: DescriptionItem[] = [
    { key: 'contactName', label: t('sponsor.contactName'), value: s.contactName ?? notSet },
    { key: 'company', label: t('sponsor.company'), value: s.company ?? notSet },
    { key: 'email', label: t('sponsor.email'), value: <span data-testid="sponsor-email">{email}</span> },
    { key: 'terms', label: t('sponsor.termsAcceptedAt'), value: when(s.termsAcceptedAt) || notSet },
    { key: 'submittedAt', label: t('sponsor.submittedAt'), value: when(s.submittedAt) || notSet },
    ...(s.approvedAt ? [{ key: 'approvedAt', label: t('sponsor.approvedAt'), value: when(s.approvedAt) }] : []),
    ...(s.rejectReason ? [{ key: 'reason', label: t('sponsor.rejectReason'), value: s.rejectReason }] : []),
  ]

  const decided = () => {
    after.decide()
    void sponsor.refetch()
    void queryClient.invalidateQueries({ queryKey: QUEUE.sponsors })
  }
  let decision
  if (!pending) {
    decision = <p data-testid="sponsor-not-pending">{t('sponsor.notPending', { status: statusLabel(s.reviewStatus) })}</p>
  } else if (after.decided) {
    decision = <Alert data-testid="sponsor-updating"><AlertDescription>{t('sponsor.updating')}</AlertDescription></Alert>
  } else if (!can(PERMISSION.reviewSponsor)) {
    decision = <p className="text-muted-foreground text-sm">{t('sponsor.notAllowed')}</p>
  } else {
    decision = <ApprovalPanel requestId={s.approvalRequestId!} onDecided={decided} />
  }

  return (
    <div className="flex flex-col gap-4" data-testid="sponsor-review" data-status={s.reviewStatus}>
      <div>{back}</div>
      <PageHeader
        title={s.company ?? s.contactName ?? userId}
        className="pb-0"
        description={
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <Link to={PATH.user(userId)} className={linkClass} data-testid="sponsor-summary">{t('sponsor.summary')}</Link>
            <Badge variant={pending ? 'secondary' : 'outline'} data-testid="sponsor-status">
              {statusLabel(s.reviewStatus)}
            </Badge>
          </span>
        }
      />
      <Section title={t('sponsor.application')} testId="sponsor-application">
        <DescriptionList items={items} columns={2} />
        <div className="flex flex-col gap-1">
          <h3 className="font-semibold">{t('sponsor.avatar')}</h3>
          {s.avatar ? <FilePreview fileId={s.avatar} field={AVATAR} width={128} />
            : <p className="text-muted-foreground text-sm">{t('sponsor.noAvatar')}</p>}
        </div>
        <div className="flex flex-col gap-1" data-testid="sponsor-intro">
          <h3 className="font-semibold">{t('sponsor.intro')}</h3>
          {s.intro ? <MarkdownView text={s.intro} /> : <p className="text-muted-foreground text-sm">{notSet}</p>}
        </div>
      </Section>
      <Section title={t('publication.decision')} testId="sponsor-decision">{decision}</Section>
    </div>
  )
}
