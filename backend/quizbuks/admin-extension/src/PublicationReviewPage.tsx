import { useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation, useParams } from 'react-router'
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
  paths,
  useAuth,
  type DescriptionItem,
} from '@jabiz/admin'
import {
  readInvitationSummary,
  readPublication,
  readPublicationFunds,
  readPublicationRow,
  readSponsor,
  readTakedowns,
  readTiers,
  readVersion,
  type Publication,
  type Takedown,
  type Tier,
  type Version,
} from './api'
import { AfterDecision, LoadFailed, PublisherLink, Section } from './components'
import type { BackTo } from './PublicationsPage'
import { forbidden, money, QUEUE, useAfterDecision, useLabels, when } from './hooks'
import { ACCOUNT_SUSPENDED, DICTIONARY, OPERATOR_PROCESS, PATH, PENDING_REVIEW, PERMISSION, PUBLICATION_ADMIN } from './ids'

/** An administrator's reason as the reader reads it: a ban's pause records a code (Q10b, review L3). */
function useAdminReason() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (reason: string | null) => (reason === ACCOUNT_SUSPENDED ? t('publication.accountSuspended') : reason)
}

/**
 * The publications of the same quiz an administrator took down, with their reasons (Q10b, review M-d): such a
 * publication goes to a person's review whatever the switch says, and the reviewer reads why before deciding.
 */
function Takedowns({ takedowns }: { takedowns: UseQueryResult<Takedown[]> }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const reasonOf = useAdminReason()
  if (takedowns.isError) {
    return (
      <Alert variant="warning" data-testid="publication-takedowns-failed">
        <AlertDescription>{t('publication.takedownsFailed')}</AlertDescription>
      </Alert>
    )
  }
  if (!takedowns.data || takedowns.data.length === 0) return null
  return (
    <Section title={t('publication.takedowns')} testId="publication-takedowns">
      <Alert variant="warning">
        <AlertDescription>{t('publication.takedownsHint')}</AlertDescription>
      </Alert>
      <ul className="flex flex-col gap-2">
        {takedowns.data.map((takedown) => (
          <li key={takedown.publicationId} className="text-sm" data-testid="publication-takedown">
            <Link to={PATH.publication(takedown.publicationId)} className={linkClass}>{takedown.name}</Link>
            {' · '}
            {takedown.endReason === 'ADMIN' ? t('publication.takedownEnded') : t('publication.takedownPaused')}
            {takedown.at && <> · {when(takedown.at)}</>}
            {takedown.adminReason && (
              <p className="text-muted-foreground" data-testid="publication-takedown-reason">
                {t('publication.adminReason')}: {reasonOf(takedown.adminReason)}
              </p>
            )}
          </li>
        ))}
      </ul>
    </Section>
  )
}
import { VersionPreview } from './VersionPreview'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/** What a read was made for: the review request the publication waited for when it was read. */
interface ForRequest<T> {
  requestId: string | null
  value: T
}

/** The sponsor's funds as far as finance sees them; not shown, nor asked for, to anyone else. */
function Funds({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const funds = useQuery({
    queryKey: ['qb-ext', 'funds', publicationId],
    queryFn: () => readPublicationFunds(publicationId),
  })
  if (funds.isError) return <LoadFailed error={funds.error} onRetry={() => void funds.refetch()} />
  if (!funds.data) return null
  return (
    <div className="flex flex-col gap-2" data-testid="publication-funds">
      <h3 className="font-semibold">{t('publication.funds')}</h3>
      <DescriptionList columns={3} items={[
        { key: 'fundsShort', label: t('publication.fundsShort'),
          value: funds.data.fundsShort ? t('common.yes') : t('common.no') },
        { key: 'pendingCount', label: t('publication.pendingCount'), value: funds.data.pendingCount.toLocaleString() },
        { key: 'pendingAmount', label: t('publication.pendingAmount'), value: money(funds.data.pendingAmount ?? '0') },
      ]} />
    </div>
  )
}

function Settings({ publication, tiers, quiz, finance }: {
  publication: Publication
  tiers: UseQueryResult<ForRequest<Tier[]>>
  quiz: string | null
  finance: boolean
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const region = useLabels(DICTIONARY.region)
  const audience = useLabels(DICTIONARY.audience)
  const notSet = t('common.notSet')
  const invited = publication.audience === 'INVITED'
  const items: DescriptionItem[] = [
    { key: 'quiz', label: t('publication.quiz'), value: quiz ?? notSet },
    { key: 'audience', label: t('publication.audience'),
      value: <span data-testid="publication-audience">{audience(publication.audience)}</span> },
    // Q25: an invited publication's list stands in for its regions.
    ...(invited ? [] : [{
      key: 'regions',
      label: t('publication.regions'),
      value: publication.regions.length > 0 ? publication.regions.map(region).join(', ') : notSet,
    }]),
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
  let tierTable
  if (tiers.isError) tierTable = <LoadFailed error={tiers.error} onRetry={() => void tiers.refetch()} />
  else if (tiers.isPending) tierTable = <PageState kind="loading" />
  else {
    tierTable = (
      <Table aria-label={t('publication.tiers')} data-testid="publication-tiers">
        <TableHeader>
          <TableRow>
            <TableHead>{t('publication.tierScores')}</TableHead>
            <TableHead className="text-right">{t('publication.tierReward')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {tiers.data.value.map((tier) => (
            <TableRow key={`${tier.fromScore}-${tier.toScore}`} data-testid="publication-tier">
              <TableCell>{t('publication.tierScoresValue', { from: tier.fromScore, to: tier.toScore })}</TableCell>
              <TableCell className="text-right tabular-nums">{money(tier.reward)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    )
  }
  return (
    <Section title={t('publication.settings')} testId="publication-settings">
      <DescriptionList items={items} columns={3} />
      <div className="flex flex-col gap-2">
        <h3 className="font-semibold">{t('publication.tiers')}</h3>
        {tierTable}
      </div>
      {invited && <Invitations publicationId={publication.publicationId} />}
      {finance && <Funds publicationId={publication.publicationId} />}
    </Section>
  )
}

/**
 * How an invited publication's list stands (Q25, `qb.admin.invitation-summary`): the counts only. The addresses are
 * the sponsor's alone (D-Q25-8); support reads them with the sponsor if ever needed.
 */
function Invitations({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const summary = useQuery({
    queryKey: ['qb-ext', 'invitation-summary', publicationId],
    queryFn: () => readInvitationSummary(publicationId),
  })
  let body
  if (summary.isError) body = <LoadFailed error={summary.error} onRetry={() => void summary.refetch()} />
  else if (summary.isPending) body = <PageState kind="loading" />
  else {
    const counts = summary.data
    body = (
      <DescriptionList columns={3} data-testid="publication-invitations" items={[
        { key: 'total', label: t('publication.invitationsTotal'), value: counts.total.toLocaleString() },
        { key: 'added', label: t('publication.invitationsAdded'), value: counts.added.toLocaleString() },
        { key: 'sent', label: t('publication.invitationsSent'), value: (counts.queued + counts.sent).toLocaleString() },
        { key: 'started', label: t('publication.invitationsStarted'), value: counts.started.toLocaleString() },
        { key: 'answered', label: t('publication.invitationsAnswered'), value: counts.answered.toLocaleString() },
      ]} />
    )
  }
  return (
    <div className="flex flex-col gap-2">
      <h3 className="font-semibold">{t('publication.invitations')}</h3>
      <p className="text-muted-foreground text-sm">{t('publication.invitationsNote')}</p>
      {body}
    </div>
  )
}

/** The frozen version, or why it is not shown (no permission: the finance administrators; no version; unreadable). */
function Content({ versionId, version }: { versionId: string | null; version: UseQueryResult<ForRequest<Version | null>> }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  let body
  if (!versionId) body = <PageState kind="warning" description={t('publication.noVersion')} />
  else if (version.isPending) body = <PageState kind="loading" />
  else if (forbidden(version.error)) body = <PageState kind="warning" description={t('publication.contentHidden')} />
  else if (version.isError) body = <LoadFailed error={version.error} onRetry={() => void version.refetch()} />
  else if (!version.data.value?.content) {
    body = <PageState kind="warning" description={t('publication.contentUnreadable')} />
  } else {
    const shown = version.data.value
    body = (
      <>
        <DescriptionList items={[{ key: 'hash', label: t('publication.contentHash'), value:
          <code className="break-all text-xs">{shown.contentHash}</code>, copy: shown.contentHash }]} />
        <VersionPreview content={shown.content!} />
      </>
    )
  }
  return <Section title={t('publication.content')} testId="publication-content">{body}</Section>
}

/**
 * A submitted publication as the reviewer decides it (02-design §7.3, P-09): who submitted it and when, every setting
 * and tier (frozen at submission), the sponsor's funds (finance only) and the whole frozen version with the correct
 * answers. The approval is bound to the version's hash, the settings and the tiers together, so the decision (the
 * platform's `ApprovalPanel`, `APPROVAL_DECIDE`) is offered only while all three are shown as read for the request the
 * publication waits for: none failed or is being read again. The tiers and the version are read per request
 * (`[publicationId, approvalRequestId]`). After the decision the page reads the publication again until its state
 * follows (the approval's event is delivered a moment later).
 */
export default function PublicationReviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { publicationId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const after = useAfterDecision()
  const statusLabel = useLabels(DICTIONARY.publicationStatus)
  const finance = can(PERMISSION.financeRead)

  const publication = useQuery({
    queryKey: ['qb-ext', 'publication', publicationId],
    queryFn: () => readPublication(publicationId),
    refetchInterval: (query) => after.interval(query.state.data?.status === PENDING_REVIEW),
    // A refetch on focus would take the decision away while it is typed (it waits for every read to settle).
    refetchOnWindowFocus: false,
  })
  const requestId = publication.data?.approvalRequestId ?? null
  const versionId = publication.data?.versionId ?? null
  const row = useQuery({
    queryKey: ['qb-ext', 'publication-row', publicationId],
    queryFn: () => readPublicationRow(publicationId),
    refetchOnWindowFocus: false,
  })
  const tiers = useQuery({
    queryKey: ['qb-ext', 'tiers', publicationId, requestId],
    queryFn: async (): Promise<ForRequest<Tier[]>> => ({ requestId, value: await readTiers(publicationId) }),
    enabled: publication.isSuccess && !!publication.data,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })
  const version = useQuery({
    queryKey: ['qb-ext', 'version', publicationId, requestId],
    queryFn: async (): Promise<ForRequest<Version | null>> => ({ requestId, value: await readVersion(versionId!) }),
    enabled: !!versionId,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    retry: (count, error) => !forbidden(error) && count < 2,
  })
  const sponsorId = publication.data?.publisherId
  const quizId = publication.data?.quizId ?? null
  const takedowns = useQuery({
    queryKey: ['qb-ext', 'takedowns', sponsorId, quizId],
    queryFn: () => readTakedowns(sponsorId!, quizId!),
    enabled: !!sponsorId && !!quizId,
    refetchOnWindowFocus: false,
  })
  const reasonOf = useAdminReason()
  const sponsor = useQuery({
    queryKey: ['qb-ext', 'sponsor', sponsorId],
    queryFn: () => readSponsor(sponsorId!),
    enabled: !!sponsorId,
  })

  // Opened from the publication list or a quiz (Q17), it goes back there; else to the review queue.
  const backTo = (useLocation().state as { backTo?: BackTo } | null)?.backTo
  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={backTo?.path ?? PATH.reviews}>
        <ArrowLeft aria-hidden />{backTo ? t(backTo.label) : t('publication.back')}
      </Link>
    </Button>
  )
  if (publication.isPending) return <PageState kind="loading" />
  if (publication.isError) return <LoadFailed error={publication.error} onRetry={() => void publication.refetch()} />
  if (!publication.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('publication.notFound')} /></div>
  }
  const p = publication.data
  const pending = p.status === PENDING_REVIEW && !!p.approvalRequestId
  const shownVersion = version.data?.value
  const quiz = shownVersion ? t('publication.quizValue', { title: shownVersion.title, label: shownVersion.label })
    : row.data?.quizTitle ? t('publication.quizValue', { title: row.data.quizTitle, label: p.versionLabel ?? '' })
      : p.versionLabel
  const company = sponsor.data?.company ?? sponsor.data?.contactName
  // Everything the approval is bound to, settled and read for the request the publication waits for.
  const everythingShown = publication.isSuccess && !publication.isFetching
    && tiers.isSuccess && !tiers.isFetching && tiers.data.requestId === p.approvalRequestId
    && version.isSuccess && !version.isFetching && version.data.requestId === p.approvalRequestId
    && !!shownVersion?.content
    // And whether the quiz was taken down before (Q10b, M-d): nothing is decided without it.
    && (!p.quizId || (takedowns.isSuccess && !takedowns.isFetching))

  const decided = (status: string) => {
    after.decide(status)
    void publication.refetch()
    void queryClient.invalidateQueries({ queryKey: QUEUE.publications })
  }

  let decision
  if (after.decided && (pending || after.levelOnly)) {
    decision = <AfterDecision after={after} onRetry={() => {
      after.retry()
      void publication.refetch()
    }} />
  } else if (!pending) {
    decision = (
      <div className="flex flex-col gap-2" data-testid="publication-not-pending">
        <p>{t('publication.notPending', { status: statusLabel(p.status) })}</p>
        {p.rejectReason && (
          <DescriptionList items={[{ key: 'reason', label: t('publication.rejectReason'), value: p.rejectReason }]} />
        )}
        {p.status === 'PAUSED' && p.pausedBy === 'ADMIN' && (
          <p data-testid="publication-admin-paused">{t('publication.adminPaused')}</p>
        )}
        {p.adminReason && (
          <DescriptionList items={[{ key: 'adminReason', label: t('publication.adminReason'),
            value: reasonOf(p.adminReason) }]} />
        )}
      </div>
    )
  } else if (!can(PERMISSION.reviewPublication)) {
    decision = <p className="text-muted-foreground text-sm">{t('publication.notAllowed')}</p>
  } else if (!everythingShown) {
    // Nothing is decided unseen: the panel waits for the settings, the tiers and the content.
    decision = <p className="text-muted-foreground text-sm" data-testid="publication-needs-content">{t('publication.needsContent')}</p>
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
              <PublisherLink kind={p.publisherKind} id={p.publisherId} label={company} testId="publication-sponsor" />
            </span>
            {p.submittedAt && <span>{t('publication.submittedAt')}: {when(p.submittedAt)}</span>}
            {p.quizId && can(PERMISSION.quizRead) && (
              <Link to={PATH.quiz(p.quizId)} className={linkClass} data-testid="publication-quiz">
                {t('publications.openQuiz')}
              </Link>
            )}
            {can(PERMISSION.usersRead) && (
              <Link to={PATH.attemptsOf({ publication: p.publicationId })} className={linkClass}
                data-testid="publication-attempts">
                {t('publications.attempts')}
              </Link>
            )}
            <Badge variant={pending ? 'secondary' : 'outline'} data-testid="publication-status">
              {statusLabel(p.status)}
            </Badge>
          </span>
        }
      />
      <Takedowns takedowns={takedowns} />
      <Settings publication={p} tiers={tiers} quiz={quiz} finance={finance} />
      <Content versionId={versionId} version={version} />
      <Section title={t('publication.decision')} testId="publication-decision">{decision}</Section>
      {can(PERMISSION.contentWrite) && <AdminActions publication={p} processes={PUBLICATION_ADMIN} />}
      {/* Q48a: an operator's member takes it down with the operator's processes (headquarters' come first). */}
      {!can(PERMISSION.contentWrite) && can(PERMISSION.operatorUse) && (
        <AdminActions publication={p} processes={OPERATOR_TAKEDOWN} />
      )}
    </div>
  )
}

/** The running states an administrator may act on (the processes' `actsOn`; the server decides all the same). */
const RUNNING = ['SCHEDULED', 'LIVE', 'FUNDS_SHORT', 'PAUSED']

/** An operator's takedowns (Q48a): the same, through the operator's processes. */
const OPERATOR_TAKEDOWN = { pause: OPERATOR_PROCESS.pause, resume: OPERATOR_PROCESS.resume, end: OPERATOR_PROCESS.end,
  version: 1, operator: true } as const

type TakedownProcesses = { pause: string; resume: string; end: string; version: number; operator?: boolean }

/**
 * Taking a publication down (Q10b, review M2): links to the platform's process pages with the publication filled in,
 * where the administrator gives the reason (the sponsor reads it) and passes a second factor.
 */
function AdminActions({ publication, processes }: { publication: Publication; processes: TakedownProcesses }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  if (!RUNNING.includes(publication.status)) return null
  const adminPaused = publication.status === 'PAUSED' && publication.pausedBy === 'ADMIN'
  // An operator does not resume or end headquarters' takedown (the server refuses it): it is shown as such.
  const headquarters = !!processes.operator && adminPaused && !publication.takedownOperatorId
  if (headquarters) {
    return (
      <Section title={t('publication.admin')} testId="publication-admin">
        <p className="text-muted-foreground text-sm" data-testid="publication-admin-hq">{t('operator.takenDownByHq')}</p>
      </Section>
    )
  }
  const link = (name: string) =>
    `${paths.process(name, processes.version)}?${new URLSearchParams({ publicationId: publication.publicationId })}`
  return (
    <Section title={t('publication.admin')} testId="publication-admin">
      <p className="text-muted-foreground text-sm">{t('publication.adminHint')}</p>
      <div className="flex flex-wrap gap-4">
        {!adminPaused && (
          <Link to={link(processes.pause)} className={linkClass} data-testid="publication-admin-pause">
            {t('publication.adminPause')}
          </Link>
        )}
        {adminPaused && (
          <Link to={link(processes.resume)} className={linkClass} data-testid="publication-admin-resume">
            {t('publication.adminResume')}
          </Link>
        )}
        <Link to={link(processes.end)} className={linkClass} data-testid="publication-admin-end">
          {t('publication.adminEnd')}
        </Link>
      </div>
    </Section>
  )
}
