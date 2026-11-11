import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Button, DescriptionList, DetailPage, PageHeader, PageState } from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { Link, navigate, Redirect, useLocation } from '../qb/router'
import { treePathOf } from '../tree/api'
import '../tree/messages'
import { conditionsQuery, readConditions, readEntryFeesCollected,
  readPublication, type Publication, type PublicationDetail } from './api'
import { formatKudos } from '../qb/kudos'
import { NEW_PUBLICATION, publicationPath, publicationQuery, PUBLICATIONS_PATH, PUBLICATIONS_QUERY,
  type PageOfPublicationTab } from './paths'
import { analyticsPath } from '../analytics/api'
import '../analytics/messages'
import { transactionsOf } from '../transactions/api'
import '../transactions/messages'
import { DigitalPoolSection } from './DigitalPool'
import { InvitationsSection } from './Invitations'
import { PublicationActions, usePublicationLanguage } from './PublicationActions'
import { PublicationQrPanel } from './PublicationQrDialog'
import { PublicationTabs } from './PublicationTabs'
import { StaleBanner, PublicationWizard } from './PublicationWizard'
import { PrepaymentsSection } from './FundsPanel'
import { ReferralsSection } from './ReferralsSection'
import { ExposureSection } from '../exposure/ExposureCounts'
import { VideoUseSection } from '../videouses/VideoUseSection'
import '../sharedvideos/messages'
import { MainlandNotice, reachesMainland, StatusBadge, SummaryBlock, useEndReasonLabel, useVersionOf,
  type SummaryValues } from './shared'
import { PreviewButton } from '../preview/PreviewButton'
import { previewOfPublication } from '../preview/paths'
import { VersionNotice } from './VersionNotice'

/** The way back to the list, above a publication's title. */
function Breadcrumb() {
  const { t } = useTranslation(SPONSOR)
  return (
    <nav aria-label={t('publishing.wizard.breadcrumb')} className="text-sm">
      <Link to={PUBLICATIONS_PATH} className="text-muted-foreground underline-offset-4 hover:underline">
        {t('publishing.list.title')}
      </Link>
    </nav>
  )
}

/** Why a draft was sent back: the reviewer's reason, or that its end passed in review. */
function SentBack({ publication }: { publication: Publication }) {
  const { t } = useTranslation(SPONSOR)
  if (!publication.rejectReason && !publication.rejectCode) return null
  const text = publication.rejectReason
    ? t('publishing.detail.sentBackReason', { reason: publication.rejectReason })
    : publication.rejectCode === 'QB_PUBLICATION_EXPIRED_IN_REVIEW'
      ? t('publishing.detail.expiredInReview')
      : publication.rejectCode === 'QB_USER_BANNED'
        ? t('publishing.detail.sentBackBanned')
        // F-S12: its shared video was taken down while it was in review.
        : publication.rejectCode === 'QB_SHARED_VIDEO_TAKEN_DOWN'
          ? t('sharedVideos.field.takenDownInReview')
          : publication.rejectCode
  return (
    <Alert variant="warning" data-testid="publication-sent-back-reason">
      <AlertTitle>{t('publishing.detail.sentBack')}</AlertTitle>
      <AlertDescription>{text}</AlertDescription>
    </Alert>
  )
}

/**
 * What an administrator did to the publication (Q10b, review M2): paused it (the sponsor cannot resume it) or ended
 * it, with the reason they gave.
 */
function AdminNotice({ publication }: { publication: Publication }) {
  const { t } = useTranslation(SPONSOR)
  const paused = publication.status === 'PAUSED' && publication.pausedBy === 'ADMIN'
  const ended = publication.status === 'ENDED' && publication.endReason === 'ADMIN'
  if (!paused && !ended) return null
  const key = paused ? 'adminPaused' : 'adminEnded'
  return (
    <Alert variant="warning" data-testid="publication-admin-notice">
      <AlertTitle>{t(`publishing.detail.${key}`)}</AlertTitle>
      <AlertDescription>
        <p>{t(`publishing.detail.${key}Text`)}</p>
        {publication.adminReason && (
          <p data-testid="publication-admin-reason">
            {t('publishing.detail.adminReason', {
              // A ban's pause records a code, not the ban's own reason (Q10b, review L3).
              reason: publication.adminReason === 'QB_ACCOUNT_SUSPENDED'
                ? t('publishing.detail.accountSuspended')
                : publication.adminReason,
            })}
          </p>
        )}
      </AlertDescription>
    </Alert>
  )
}

/** Whether the publication hands out digital prizes from a pool of codes (Q33): its page then carries the pool. */
const pooled = (publication: Publication) =>
  publication.rewardMethod === 'DIGITAL_PRIZE' && publication.digitalDelivery === 'POOL'
/** Q89b-2: a pool of another sponsor's digital product is its provider's to load; the publisher follows it. */
const poolMode = (publication: Publication) => (publication.benefitPurchaseId ? 'watch' : 'own')

/** A submitted publication: frozen (D-Q4-2), shown as it is with what can still be done in its state. */
function PublicationView({ detail, stale, onStale, tab }: {
  detail: PublicationDetail
  stale: boolean
  onStale: () => void
  tab: PageOfPublicationTab
}) {
  const { t } = useTranslation(SPONSOR)
  const { publication, tiers } = detail
  const { versions, version } = useVersionOf(publication.quizId, publication.versionId)
  // F-S11: the version shown, or the one a switch in review would show, has a video mainland China cannot play.
  const pendingVersion = versions.data?.find((v) => v.versionId === publication.pendingVersionId)
  const reaches = reachesMainland(publication.audience, publication.regions)
  // Q47: the conditions it was submitted with (frozen with it).
  const conditions = useQuery({ queryKey: conditionsQuery(publication.publicationId),
    queryFn: () => readConditions(publication.publicationId), enabled: publication.audience === 'PUBLIC' })
  const values = {
    ...publication,
    quizTitle: version?.title ?? null,
    questionCount: version?.questionCount ?? null,
    // F-S7: whether the version shuffles its questions (unknown while it is read: not shown).
    shuffled: version?.shuffled,
    tiers,
    conditions: conditions.data,
  }
  const id = publication.publicationId
  return (
    <div data-testid="publication-view" data-status={publication.status} data-tab={tab}>
      <DetailPage breadcrumb={<Breadcrumb />} title={<span data-testid="publication-title">{publication.name}</span>}
        description={
          <span className="text-muted-foreground text-sm">
            {t('publishing.detail.edited', { time: formatDateTime(publication.editedAt) })}
          </span>
        }
        tabs={<PublicationTabs publicationId={id} current={tab} />}
        asideLabel={t('publishing.tabs.aside')}
        aside={<>
          <h2 className="text-sm font-medium">{t('publishing.tabs.status')}</h2>
          <StatusBadge status={publication.status} startAt={publication.startAt} endAt={publication.endAt} />
          <p className="text-muted-foreground text-sm">
            {publication.status === 'PENDING_REVIEW' ? t('publishing.detail.inReview') : t('publishing.detail.frozen')}
          </p>
          <div className="flex flex-wrap gap-2">
            <PreviewButton to={previewOfPublication(id)} />
            <PublicationActions publication={publication} revision={publication.revision} disabled={stale}
              onStale={onStale} />
          </div>
          {publication.status !== 'PENDING_REVIEW' && (
            <nav aria-label={t('publishing.tabs.links')} className="flex flex-col gap-1 text-sm">
              <Link to={analyticsPath(id)} className="w-fit underline underline-offset-4"
                data-testid="publication-analytics-link">
                {t('analytics.publication.link')}
              </Link>
              <Link to={transactionsOf(id)} className="w-fit underline underline-offset-4"
                data-testid="publication-transactions">
                {t('transactions.publicationLink')}
              </Link>
              <Link to={treePathOf(publication.treeNodeId)} className="w-fit underline underline-offset-4"
                data-testid="publication-tree">
                {t('tree.onTree')}
              </Link>
            </nav>
          )}
        </>}>
        {tab === 'overview' && <OverviewTab publication={publication} values={values} />}
        {tab === 'questions' && (
          <>
            <SummaryBlock values={values} part="quiz" />
            <VersionNotice publication={publication} disabled={stale} onStale={onStale} />
          </>
        )}
        {tab === 'audience' && (
          <>
            {reaches && version?.foreignVideo && <MainlandNotice testId="publication-mainland" />}
            {reaches && !version?.foreignVideo && pendingVersion?.foreignVideo && (
              <MainlandNotice testId="publication-mainland-pending" pending />
            )}
            <SummaryBlock values={values} part="audience" />
            {publication.audience === 'INVITED' && (
              <InvitationsSection publicationId={id} status={publication.status} />
            )}
          </>
        )}
        {tab === 'rewards' && (
          <>
            <SummaryBlock values={values} part="rewards" />
            {publication.referral && <ReferralsSection publicationId={id} />}
            {pooled(publication) && <DigitalPoolSection publicationId={id} status={publication.status}
              mode={poolMode(publication)} />}
          </>
        )}
        {tab === 'fees' && (
          <>
            <p className="text-muted-foreground text-sm" data-testid="publication-fees-hint">
              {t('publishing.tabs.feesHint')}
            </p>
            {/* F-S13: the exposures of another Sponsor's shared video it uses (nothing for its own videos). */}
            <ExposureSection publicationId={id} />
            {/* F-S14: the ledger of that shared video and both sides' figures (nothing for its own videos). */}
            <VideoUseSection publicationId={id} />
            {/* F-M5: what its submission set aside, and what became of it (nothing while there are no rows). */}
            <PrepaymentsSection publicationId={id} />
          </>
        )}
        {tab === 'share' && <ShareTab publication={publication} />}
      </DetailPage>
    </div>
  )
}

/** The overview: what happened to the publication, what it earned, its name, period and dates. */
function OverviewTab({ publication, values }: { publication: Publication; values: SummaryValues }) {
  const { t } = useTranslation(SPONSOR)
  const endReason = useEndReasonLabel()
  const dates = [
    { key: 'submittedAt', value: publication.submittedAt },
    { key: 'approvedAt', value: publication.approvedAt },
    { key: 'pausedAt', value: publication.pausedAt },
    { key: 'endedAt', value: publication.endedAt },
  ].filter((d) => d.value)
  return (
    <>
      {publication.status === 'FUNDS_SHORT' && (
        <Alert variant="warning"><AlertDescription>{t('publishing.detail.fundsShort')}</AlertDescription></Alert>
      )}
      <AdminNotice publication={publication} />
      <SummaryBlock values={values} part="overview" />
      {publication.entryFee > 0 && (
        <EntryFeesCollected publicationId={publication.publicationId} fee={publication.entryFee} />
      )}
      {dates.length > 0 && (
        <div className="flex flex-col gap-2">
          <h2 className="text-sm font-medium">{t('publishing.detail.dates')}</h2>
          <DescriptionList items={[
            ...dates.map((d) => ({ key: d.key, label: t(`publishing.detail.${d.key}`), value: formatDateTime(d.value!) })),
            ...(publication.endReason
              ? [{ key: 'endReason', label: t('publishing.detail.endReason'), value: endReason(publication.endReason) }]
              : []),
          ]} />
        </div>
      )}
    </>
  )
}

/** The share link and its QR code (the dialog of the page's head shows the same). */
function ShareTab({ publication }: { publication: Publication }) {
  const { t } = useTranslation(SPONSOR)
  const language = usePublicationLanguage(publication.versionId)
  return (
    <section className="flex flex-col gap-4" aria-label={t('publishing.tabs.share')} data-testid="publication-share-panel">
      <p className="text-muted-foreground text-sm">{t('publishing.tabs.shareText')}</p>
      <PublicationQrPanel publicationId={publication.publicationId} language={language}
        languages={publication.languages}
        names={Object.fromEntries(Object.entries(publication.translations).map(([l, texts]) => [l, texts?.name]))}
        name={publication.name} />
    </section>
  )
}

/**
 * A publication's page (`/publications/new`, `/publications/<id>`): a draft opens in the wizard, a submitted one as it
 * is with its actions. Only the sponsor's own publications are found; anything else is "does not exist".
 */
export function PublicationPage({ publicationId, tab = 'overview' }: {
  publicationId: string
  /** The tab of a submitted publication (F-F3c); a draft is the wizard whatever the address says. */
  tab?: PageOfPublicationTab
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const { search } = useLocation()
  // The step to open at: given once, by the address a new draft was opened at after its first save.
  const [initialStep] = useState(() => Number(new URLSearchParams(search).get('step') ?? 0) || 0)
  const [stale, setStale] = useState(false)
  // A reload starts the wizard again from the publication as read.
  const [generation, setGeneration] = useState(0)
  const isNew = publicationId === NEW_PUBLICATION
  const found = useQuery({
    queryKey: publicationQuery(publicationId),
    queryFn: () => readPublication(publicationId),
    enabled: !isNew,
  })

  const reload = async () => {
    await queryClient.invalidateQueries({ queryKey: PUBLICATIONS_QUERY })
    setStale(false)
    setGeneration((g) => g + 1)
  }
  const banner = stale ? <StaleBanner onReload={() => void reload()} /> : null

  const draftHeader = (detail: PublicationDetail | null) => (revision: number): ReactNode => (
    <>
      <PageHeader breadcrumb={<Breadcrumb />}
        title={<span data-testid="publication-title">{detail?.publication.name || t('publishing.wizard.newTitle')}</span>}
        description={detail && (
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={detail.publication.status} startAt={null} endAt={null} />
            <span className="text-muted-foreground text-sm">
              {t('publishing.detail.edited', { time: formatDateTime(detail.publication.editedAt) })}
            </span>
          </span>
        )}
        actions={detail && <div className="flex flex-wrap gap-2">
          <PreviewButton to={previewOfPublication(detail.publication.publicationId)} />
          <PublicationActions publication={detail.publication} revision={revision}
            disabled={stale} onStale={() => setStale(true)} />
        </div>} />
      {banner}
      {detail && <SentBack publication={detail.publication} />}
    </>
  )

  if (isNew) {
    return <PublicationWizard detail={null} initialStep={0} stale={false} onStale={() => setStale(true)}
      header={draftHeader(null)} />
  }
  if (found.isPending) return <PageState kind="loading" />
  if (found.isError) {
    return <PageState kind="error" titleAs="h1" description={tQb('failed')}
      action={<Button variant="outline" onClick={() => void found.refetch()}>{tQb('retry')}</Button>} />
  }
  if (!found.data) {
    return <PageState kind="notFound" titleAs="h1" description={t('publishing.detail.notFound')}
      action={<Button variant="outline" onClick={() => navigate(PUBLICATIONS_PATH)}>
        {t('publishing.detail.backToList')}
      </Button>} />
  }
  const detail = found.data
  if (detail.publication.status === 'DRAFT') {
    // A draft has no tabs: an address of one goes back to the draft.
    if (tab !== 'overview') return <Redirect to={publicationPath(publicationId)} />
    // A draft's codes are loaded below the wizard once the saved draft hands out prizes from a pool (Q33): the
    // readiness check wants the pool to cover the winners before it is submitted.
    return (
      <div className="flex flex-col gap-8">
        <PublicationWizard key={generation} detail={detail} initialStep={initialStep} stale={stale}
          onStale={() => setStale(true)} header={draftHeader(detail)} />
        {pooled(detail.publication) && (
          <DigitalPoolSection publicationId={detail.publication.publicationId} status={detail.publication.status}
            mode={poolMode(detail.publication)} />
        )}
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-4">
      {banner}
      <PublicationView key={generation} detail={detail} stale={stale} onStale={() => setStale(true)} tab={tab} />
    </div>
  )
}

/** Q88b (D-Q88-16): what the publication's takers paid to enter it (settled into the cash account daily). */
function EntryFeesCollected({ publicationId, fee }: { publicationId: string; fee: number }) {
  const { t } = useTranslation(SPONSOR)
  const collected = useQuery({ queryKey: ['qb', 'sponsor', 'entry-fees', publicationId],
    queryFn: () => readEntryFeesCollected(publicationId) })
  return (
    <p className="text-muted-foreground text-sm" data-testid="publication-entry-fees">
      {t('publishing.detail.entryFeesCollected', { fee: formatKudos(fee),
        collected: collected.data === undefined ? '…' : formatKudos(collected.data) })}
    </p>
  )
}
