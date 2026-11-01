import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  ApprovalPanel,
  Button,
  DescriptionList,
  EXTENSION_NAMESPACE,
  FilePreview,
  MarkdownView,
  PageHeader,
  PageState,
  paths,
  useAuth,
  type DescriptionItem,
} from '@jabiz/admin'
import { AfterDecision, LoadFailed, Section, StatusBadge } from './components'
import { httpsUrl } from './links'
import { QUEUE, useAfterDecision, useLabels, when } from './hooks'
import {
  ACCOUNT_SUSPENDED,
  DICTIONARY,
  PATH,
  PERMISSION,
  AVATAR_VARIANTS,
  SPONSOR_PAGE_IMAGE_VARIANTS,
  SPONSOR_PAGE_PENDING,
  SPONSOR_PAGE_PROCESS,
  SPONSOR_PAGE_PUBLISHED,
} from './ids'
import { readSponsorPage } from './page-api'
import { SponsorProductsTable } from './SponsorProductsPage'
import { SponsorVisits } from './SponsorVisits'

const IMAGE = { image: true, variants: SPONSOR_PAGE_IMAGE_VARIANTS }
const AVATAR = { image: true, variants: AVATAR_VARIANTS }
const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'


/**
 * A sponsor's page (Q60a-3; docs/quizbuks/plans/Q60-sponsor-page.md) as the reviewer and whoever may take it down
 * see it: the content its approval is bound to (link name, language, headline, cover, website), the logo and the
 * "About us" it shows from the sponsor's profile (Q65b: the onboarding's avatar and introduction, reviewed with the
 * onboarding, not with the page), and its state. The decision is the platform's `ApprovalPanel` while it waits; a published page is taken down through the
 * platform's process page with the sponsor filled in, where the reason is given with a second factor: headquarters'
 * process, or the operator's for the sponsors it looks after.
 */
export default function SponsorPageReviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { userId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const after = useAfterDecision()
  const statusLabel = useLabels(DICTIONARY.sponsorPageStatus)
  const languageLabel = useLabels(DICTIONARY.contentLanguage)
  const page = useQuery({
    queryKey: ['qb-ext', 'sponsor-page', userId],
    queryFn: () => readSponsorPage(userId),
    refetchInterval: (query) => after.interval(query.state.data?.status === SPONSOR_PAGE_PENDING),
    refetchOnWindowFocus: false,
  })

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.sponsorPages}><ArrowLeft aria-hidden />{t('sponsorPages.back')}</Link>
    </Button>
  )
  if (page.isPending) return <PageState kind="loading" />
  if (page.isError) return <LoadFailed error={page.error} onRetry={() => void page.refetch()} />
  if (!page.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('sponsorPages.notFound')} /></div>
  }
  const p = page.data
  const pending = p.status === SPONSOR_PAGE_PENDING && !!p.approvalRequestId
  const notSet = t('common.notSet')
  const website = httpsUrl(p.website)
  const items: DescriptionItem[] = [
    { key: 'sponsor', label: t('sponsorPages.sponsor'), value: p.company ?? notSet },
    { key: 'link', label: t('sponsorPages.link'), value: <span data-testid="sponsor-page-handle">/s/{p.handle}</span> },
    { key: 'language', label: t('sponsorPages.language'), value: languageLabel(p.language) },
    { key: 'website', label: t('sponsorPages.website'), value: website
      ? <a href={website} target="_blank" rel="noopener noreferrer nofollow" className={linkClass}
        data-testid="sponsor-page-website">{website}</a>
      // Not made a link, but the reviewer reads what the page holds.
      : p.website ? <span className="break-all" data-testid="sponsor-page-website-text">{p.website}</span> : notSet },
    ...(p.submittedAt ? [{ key: 'submittedAt', label: t('sponsorPages.submittedAt'), value: when(p.submittedAt) }] : []),
    ...(p.publishedAt ? [{ key: 'publishedAt', label: t('sponsorPages.publishedAt'), value: when(p.publishedAt) }] : []),
    ...(p.rejectReason ? [{ key: 'reject', label: t('sponsorPages.rejectReason'), value: p.rejectReason }] : []),
    ...(p.takenDownAt ? [{ key: 'takenDownAt', label: t('sponsorPages.takenDownAt'), value: when(p.takenDownAt) }] : []),
    ...(p.takedownReason ? [{ key: 'takedownReason', label: t('sponsorPages.takedownReason'),
      // A ban's takedown records a code, not the ban's own reason.
      value: p.takedownReason === ACCOUNT_SUSPENDED ? t('publication.accountSuspended') : p.takedownReason }] : []),
  ]

  const decided = (status: string) => {
    after.decide(status)
    void page.refetch()
    void queryClient.invalidateQueries({ queryKey: QUEUE.sponsorPages })
    void queryClient.invalidateQueries({ queryKey: ['qb-ext', 'sponsor-pages'] })
  }
  let decision
  if (after.decided && (pending || after.levelOnly)) {
    decision = <AfterDecision after={after} onRetry={() => {
      after.retry()
      void page.refetch()
    }} />
  } else if (!pending) {
    decision = <p data-testid="sponsor-page-not-pending">{t('sponsorPages.notPending', { status: statusLabel(p.status) })}</p>
  } else if (!can(PERMISSION.reviewSponsor)) {
    decision = <p className="text-muted-foreground text-sm">{t('sponsorPages.notAllowed')}</p>
  } else {
    decision = <ApprovalPanel requestId={p.approvalRequestId!} onDecided={decided} />
  }
  // Headquarters takes pages down with its process; an operator's member with the operator's (the server limits it to
  // the sponsors the operator looks after).
  const takedown = can(PERMISSION.contentWrite) ? SPONSOR_PAGE_PROCESS.takedown
    : can(PERMISSION.operatorUse) ? SPONSOR_PAGE_PROCESS.operatorTakedown : null

  return (
    <div className="flex flex-col gap-4" data-testid="sponsor-page-review" data-status={p.status}>
      <div>{back}</div>
      <PageHeader title={p.company ?? p.handle} className="pb-0" description={
        <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <StatusBadge code={p.status} label={statusLabel(p.status)} testId="sponsor-page-status" />
          {p.reviewRequired && <span className="text-muted-foreground text-sm">{t('sponsorPages.reviewRequired')}</span>}
        </span>
      } />
      <Section title={t('sponsorPages.content')} testId="sponsor-page-content">
        <DescriptionList items={items} columns={2} />
        <div className="flex flex-col gap-1">
          <h3 className="font-semibold">{t('sponsorPages.headline')}</h3>
          <p lang={p.language} data-testid="sponsor-page-headline">{p.headline}</p>
        </div>
        {p.languages.length > 1 && (
          <div className="flex flex-col gap-1" data-testid="sponsor-page-languages">
            <h3 className="font-semibold">{t('sponsorPages.languages')}</h3>
            <p className="text-sm">{p.languages.map((l) => t(`preview.languages.${l}`, { defaultValue: l })).join(', ')}</p>
            {p.languages.filter((l) => l !== p.language).map((language) => (
              <p key={language} className="text-sm" lang={language} data-testid="sponsor-page-headline-translation">
                <span className="text-muted-foreground">{t(`preview.languages.${language}`, { defaultValue: language })}: </span>
                {p.translations[language]?.headline ?? t('common.notSet')}
              </p>
            ))}
          </div>
        )}
        <p className="text-muted-foreground text-sm" data-testid="sponsor-page-from-profile">
          {t('sponsorPages.fromProfile')}</p>
        <div className="flex flex-col gap-1" data-testid="sponsor-page-story">
          <h3 className="font-semibold">{t('sponsorPages.story')}</h3>
          {p.story ? <div lang={p.language}><MarkdownView text={p.story} /></div>
            : <p className="text-muted-foreground text-sm">{notSet}</p>}
        </div>
        <div className="flex flex-wrap gap-6">
          <div className="flex flex-col gap-1">
            <h3 className="font-semibold">{t('sponsorPages.logo')}</h3>
            {p.logo ? <FilePreview fileId={p.logo} field={AVATAR} width={128} />
              : <p className="text-muted-foreground text-sm">{t('sponsorPages.noImage')}</p>}
          </div>
          <div className="flex flex-col gap-1">
            <h3 className="font-semibold">{t('sponsorPages.cover')}</h3>
            {p.cover ? <FilePreview fileId={p.cover} field={IMAGE} width={320} />
              : <p className="text-muted-foreground text-sm">{t('sponsorPages.noImage')}</p>}
          </div>
        </div>
      </Section>
      <Section title={t('sponsorPages.decision')} testId="sponsor-page-decision">{decision}</Section>
      {/* Q60b-3: the sponsor's products, shown with the page and taken down here too. */}
      <Section title={t('sponsorProducts.section')} testId="sponsor-page-products">
        <SponsorProductsTable userId={userId} />
      </Section>
      {/* Q60c-2: what the page and the products drew, approximately. */}
      <Section title={t('sponsorProducts.visits')} testId="sponsor-page-visits">
        <SponsorVisits userId={userId} />
      </Section>
      {takedown && p.status === SPONSOR_PAGE_PUBLISHED && (
        <Section title={t('sponsorPages.takedownTitle')} testId="sponsor-page-takedown">
          <p className="text-muted-foreground text-sm">{t('sponsorPages.takedownHint')}</p>
          <div>
            <Link to={`${paths.process(takedown, SPONSOR_PAGE_PROCESS.version)}?${new URLSearchParams({ userId })}`}
              className={linkClass} data-testid="sponsor-page-takedown-link">{t('sponsorPages.takedown')}</Link>
          </div>
        </Section>
      )}
    </div>
  )
}
