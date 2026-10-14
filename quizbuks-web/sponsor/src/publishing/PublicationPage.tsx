import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Button, DescriptionList, PageHeader, PageState } from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { Link, navigate, useLocation } from '../qb/router'
import { readPublication, type Publication, type PublicationDetail } from './api'
import { NEW_PUBLICATION, publicationQuery, PUBLICATIONS_PATH, PUBLICATIONS_QUERY } from './paths'
import { PublicationActions } from './PublicationActions'
import { StaleBanner, PublicationWizard } from './PublicationWizard'
import { PublicationSummary, StatusBadge, useEndReasonLabel, useVersionOf } from './shared'

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
      : publication.rejectCode
  return (
    <Alert variant="warning" data-testid="publication-sent-back-reason">
      <AlertTitle>{t('publishing.detail.sentBack')}</AlertTitle>
      <AlertDescription>{text}</AlertDescription>
    </Alert>
  )
}

/** A submitted publication: frozen (D-Q4-2), shown as it is with what can still be done in its state. */
function PublicationView({ detail, stale, onStale }: {
  detail: PublicationDetail
  stale: boolean
  onStale: () => void
}) {
  const { t } = useTranslation(SPONSOR)
  const endReason = useEndReasonLabel()
  const { publication, tiers } = detail
  const { version } = useVersionOf(publication.quizId, publication.versionId)
  const dates = [
    { key: 'submittedAt', value: publication.submittedAt },
    { key: 'approvedAt', value: publication.approvedAt },
    { key: 'pausedAt', value: publication.pausedAt },
    { key: 'endedAt', value: publication.endedAt },
  ].filter((d) => d.value)
  return (
    <section className="flex flex-col gap-4" data-testid="publication-view" data-status={publication.status}>
      <PageHeader breadcrumb={<Breadcrumb />} title={<span data-testid="publication-title">{publication.name}</span>}
        description={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={publication.status} startAt={publication.startAt} endAt={publication.endAt} />
            <span className="text-muted-foreground text-sm">
              {t('publishing.detail.edited', { time: formatDateTime(publication.editedAt) })}
            </span>
          </span>
        }
        actions={<PublicationActions publication={publication} revision={publication.revision} disabled={stale}
          onStale={onStale} />} />
      <p className="text-muted-foreground text-sm">
        {publication.status === 'PENDING_REVIEW' ? t('publishing.detail.inReview') : t('publishing.detail.frozen')}
      </p>
      {publication.status === 'FUNDS_SHORT' && (
        <Alert variant="warning"><AlertDescription>{t('publishing.detail.fundsShort')}</AlertDescription></Alert>
      )}
      <PublicationSummary values={{
        ...publication,
        quizTitle: version?.title ?? null,
        tiers,
      }} />
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
    </section>
  )
}

/**
 * A publication's page (`/publications/new`, `/publications/<id>`): a draft opens in the wizard, a submitted one as it
 * is with its actions. Only the sponsor's own publications are found; anything else is "does not exist".
 */
export function PublicationPage({ publicationId }: { publicationId: string }) {
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
        actions={detail && <PublicationActions publication={detail.publication} revision={revision}
          disabled={stale} onStale={() => setStale(true)} />} />
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
    return <PublicationWizard key={generation} detail={detail} initialStep={initialStep} stale={stale}
      onStale={() => setStale(true)} header={draftHeader(detail)} />
  }
  return (
    <div className="flex flex-col gap-4">
      {banner}
      <PublicationView key={generation} detail={detail} stale={stale} onStale={() => setStale(true)} />
    </div>
  )
}
