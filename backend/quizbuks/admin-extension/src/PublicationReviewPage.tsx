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
  PageHeader,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  UserName,
  useAuth,
  type DescriptionItem,
} from '@jabiz/admin'
import {
  readPublication,
  readPublicationRow,
  readSponsor,
  readTiers,
  readVersion,
  type Publication,
  type PublicationRow,
  type Tier,
} from './api'
import { DICTIONARY, PATH, PENDING_REVIEW, PERMISSION } from './ids'
import { LoadFailed, Section } from './components'
import { forbidden, money, QUEUE, useAfterDecision, useLabels, when } from './hooks'
import { VersionPreview } from './VersionPreview'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

function Settings({ publication, row, tiers, quiz }: {
  publication: Publication
  row: PublicationRow | null
  tiers: Tier[] | undefined
  quiz: string | null
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const region = useLabels(DICTIONARY.region)
  const notSet = t('common.notSet')
  const items: DescriptionItem[] = [
    { key: 'quiz', label: t('publication.quiz'), value: quiz ?? notSet },
    {
      key: 'regions',
      label: t('publication.regions'),
      value: publication.regions.length > 0 ? publication.regions.map(region).join(', ') : notSet,
    },
    {
      key: 'period',
      label: t('publication.period'),
      value: t('publication.periodValue', { start: when(publication.startAt) || notSet,
        end: when(publication.endAt) || notSet }),
    },
    {
      key: 'timeLimit',
      label: t('publication.timeLimit'),
      value: publication.timeLimitSec == null ? t('publication.noTimeLimit')
        : t('common.seconds', { seconds: publication.timeLimitSec.toLocaleString() }),
    },
    {
      key: 'passScore',
      label: t('publication.passScore'),
      value: publication.passScore == null ? notSet
        : t('publication.passScoreValue', { pass: publication.passScore, full: publication.fullScore ?? '?' }),
    },
    { key: 'budgetCap', label: t('publication.budgetCap'), value: money(publication.budgetCap) || notSet },
    { key: 'maxReward', label: t('publication.maxReward'), value: money(publication.maxReward) || notSet },
    { key: 'maxSpend', label: t('publication.maxSpend'), value: money(publication.maxSpend) || notSet },
    {
      key: 'maxWinners',
      label: t('publication.maxWinners'),
      value: publication.maxWinners == null ? notSet : publication.maxWinners.toLocaleString(),
    },
  ]
  return (
    <Section title={t('publication.settings')} testId="publication-settings">
      <DescriptionList items={items} columns={3} />
      <div className="flex flex-col gap-2">
        <h3 className="font-semibold">{t('publication.tiers')}</h3>
        <Table aria-label={t('publication.tiers')} data-testid="publication-tiers">
          <TableHeader>
            <TableRow>
              <TableHead>{t('publication.tierScores')}</TableHead>
              <TableHead className="text-right">{t('publication.tierReward')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(tiers ?? []).map((tier) => (
              <TableRow key={`${tier.fromScore}-${tier.toScore}`} data-testid="publication-tier">
                <TableCell>{t('publication.tierScoresValue', { from: tier.fromScore, to: tier.toScore })}</TableCell>
                <TableCell className="text-right tabular-nums">{money(tier.reward)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {row && (
        <div className="flex flex-col gap-2" data-testid="publication-funds">
          <h3 className="font-semibold">{t('publication.funds')}</h3>
          <DescriptionList columns={3} items={[
            { key: 'fundsShort', label: t('publication.fundsShort'), value: row.fundsShort ? t('common.yes') : t('common.no') },
            { key: 'pendingCount', label: t('publication.pendingCount'), value: row.pendingCount.toLocaleString() },
            { key: 'pendingAmount', label: t('publication.pendingAmount'), value: money(row.pendingAmount ?? '0') },
          ]} />
          <p className="text-muted-foreground text-sm">{t('publication.fundsHint')}</p>
        </div>
      )}
    </Section>
  )
}

/** The frozen version, or why it is not shown (no permission: the finance administrators; no version; unreadable). */
function Content({ versionId }: { versionId: string | null }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const version = useQuery({
    queryKey: ['qb-ext', 'version', versionId],
    queryFn: () => readVersion(versionId!),
    enabled: !!versionId,
    staleTime: Infinity,
    retry: (count, error) => !forbidden(error) && count < 2,
  })
  let body
  if (!versionId) body = <PageState kind="warning" description={t('publication.noVersion')} />
  else if (version.isPending) body = <PageState kind="loading" />
  else if (forbidden(version.error)) body = <PageState kind="warning" description={t('publication.contentHidden')} />
  else if (version.isError) body = <LoadFailed error={version.error} onRetry={() => void version.refetch()} />
  else if (!version.data?.content) body = <PageState kind="warning" description={t('publication.contentUnreadable')} />
  else {
    body = (
      <>
        <DescriptionList items={[{ key: 'hash', label: t('publication.contentHash'), value:
          <code className="break-all text-xs">{version.data.contentHash}</code>, copy: version.data.contentHash }]} />
        <VersionPreview content={version.data.content} />
      </>
    )
  }
  return <Section title={t('publication.content')} testId="publication-content">{body}</Section>
}

/**
 * A submitted publication as the reviewer decides it (02-design §7.3, P-09): who submitted it and when, every setting
 * and tier (frozen at submission), the sponsor's funds as far as the queue shows them, and the whole frozen version
 * with the correct answers. The decision is the platform's `ApprovalPanel` (`APPROVAL_DECIDE`): offered while the
 * publication waits for review and the version could be shown. After it, the page reads the publication again until
 * its state follows the decision (the approval's event is delivered a moment later).
 */
export default function PublicationReviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { publicationId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const after = useAfterDecision()
  const statusLabel = useLabels(DICTIONARY.publicationStatus)

  const publication = useQuery({
    queryKey: ['qb-ext', 'publication', publicationId],
    queryFn: () => readPublication(publicationId),
    refetchInterval: (query) => after.interval(query.state.data?.status === PENDING_REVIEW),
  })
  const row = useQuery({
    queryKey: ['qb-ext', 'publication-row', publicationId],
    queryFn: () => readPublicationRow(publicationId),
  })
  const tiers = useQuery({ queryKey: ['qb-ext', 'tiers', publicationId], queryFn: () => readTiers(publicationId) })
  const sponsorId = publication.data?.sponsorId
  const sponsor = useQuery({
    queryKey: ['qb-ext', 'sponsor', sponsorId],
    queryFn: () => readSponsor(sponsorId!),
    enabled: !!sponsorId,
  })
  const versionId = publication.data?.versionId ?? null
  const version = useQuery({
    queryKey: ['qb-ext', 'version', versionId],
    queryFn: () => readVersion(versionId!),
    enabled: !!versionId,
    staleTime: Infinity,
    retry: (count, error) => !forbidden(error) && count < 2,
  })

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.reviews}><ArrowLeft aria-hidden />{t('publication.back')}</Link>
    </Button>
  )
  if (publication.isPending) return <PageState kind="loading" />
  if (publication.isError) return <LoadFailed error={publication.error} onRetry={() => void publication.refetch()} />
  if (!publication.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('publication.notFound')} /></div>
  }
  const p = publication.data
  const pending = p.status === PENDING_REVIEW && !!p.approvalRequestId
  const quiz = version.data ? t('publication.quizValue', { title: version.data.title, label: version.data.label })
    : row.data?.quizTitle ? t('publication.quizValue', { title: row.data.quizTitle, label: p.versionLabel ?? '' })
      : p.versionLabel
  const company = sponsor.data?.company ?? sponsor.data?.contactName

  const decided = () => {
    after.decide()
    void publication.refetch()
    void queryClient.invalidateQueries({ queryKey: QUEUE.publications })
  }

  let decision
  if (!pending) {
    decision = (
      <div className="flex flex-col gap-2" data-testid="publication-not-pending">
        <p>{t('publication.notPending', { status: statusLabel(p.status) })}</p>
        {p.rejectReason && (
          <DescriptionList items={[{ key: 'reason', label: t('publication.rejectReason'), value: p.rejectReason }]} />
        )}
      </div>
    )
  } else if (after.decided) {
    decision = <Alert data-testid="publication-updating"><AlertDescription>{t('publication.updating')}</AlertDescription></Alert>
  } else if (!can(PERMISSION.reviewPublication)) {
    decision = <p className="text-muted-foreground text-sm">{t('publication.notAllowed')}</p>
  } else if (!version.data?.content) {
    // Nothing is decided unseen: the panel waits for the content (and is not offered without it).
    decision = <p className="text-muted-foreground text-sm">{t('publication.needsContent')}</p>
  } else {
    decision = (
      <>
        <p className="text-muted-foreground text-sm">{t('publication.decisionHint')}</p>
        <ApprovalPanel requestId={p.approvalRequestId!} onDecided={decided} />
      </>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid="publication-review" data-status={p.status}>
      <div>{back}</div>
      <PageHeader
        title={p.name}
        className="pb-0"
        description={
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span>
              {t('publication.sponsor')}:{' '}
              <Link to={PATH.user(p.sponsorId)} className={linkClass} data-testid="publication-sponsor">
                {company ?? <UserName id={p.sponsorId} />}
              </Link>
            </span>
            {p.submittedAt && <span>{t('publication.submittedAt')}: {when(p.submittedAt)}</span>}
            <Badge variant={pending ? 'secondary' : 'outline'} data-testid="publication-status">
              {statusLabel(p.status)}
            </Badge>
          </span>
        }
      />
      <Settings publication={p} row={row.data ?? null} tiers={tiers.data} quiz={quiz} />
      <Content versionId={versionId} />
      <Section title={t('publication.decision')} testId="publication-decision">{decision}</Section>
    </div>
  )
}
