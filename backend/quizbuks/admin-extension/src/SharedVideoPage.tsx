import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  ApprovalPanel,
  Button,
  DescriptionList,
  EXTENSION_NAMESPACE,
  PageHeader,
  PageState,
  paths,
  useAuth,
  type DescriptionItem,
} from '@jabiz/admin'
import { AfterDecision, LoadFailed, PublisherLink, Section, StatusBadge } from './components'
import { QUEUE, useAfterDecision, useLabels, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION, SHARED_VIDEO_PROCESS } from './ids'
import { httpsUrl } from './links'
import { duration, readSharedVideo, readSharedVideoUses, takeable } from './shared-video-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const LANGUAGES = ['zh', 'ja', 'en'] as const

/**
 * One shared video (F-S12) as its reviewer and whoever may take it down see it: what was shared (the titles, the
 * link to watch it on its site, length, orientation and topics), its state and reasons, and how many publications use
 * it. The review is the platform's approval panel while it waits (the provider's operator's reviewers decide); an
 * approved video, also one withdrawn that publications still use, is taken down on the platform's process page with
 * the video filled in, where the reason is given with a second factor.
 *
 * F-C3: the publications that went on with it, each linked, with its Sponsor, state and, once ended, why and when: after
 * a takedown, those it ended (the count of those running drops to none). An operator reads its own Sponsors' only.
 */
export default function SharedVideoPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { sharedVideoId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const after = useAfterDecision()
  const video = useQuery({
    queryKey: [...QUEUE.sharedVideos, 'one', sharedVideoId],
    queryFn: () => readSharedVideo(sharedVideoId),
    refetchInterval: (query) => after.interval(query.state.data?.status === 'PENDING'),
    refetchOnWindowFocus: false,
  })
  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.sharedVideos}><ArrowLeft aria-hidden />{t('sharedVideos.back')}</Link>
    </Button>
  )
  if (video.isPending) return <PageState kind="loading" />
  if (video.isError) return <LoadFailed error={video.error} onRetry={() => void video.refetch()} />
  if (!video.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('sharedVideos.notFound')} /></div>
  }
  const v = video.data
  const notSet = t('common.notSet')
  const link = httpsUrl(v.url)
  const items: DescriptionItem[] = [
    ...LANGUAGES.map((language) => ({ key: `title-${language}`, label: t(`sharedVideos.titleIn.${language}`),
      value: <span lang={language}>{v.titles[language] ?? notSet}</span> })),
    { key: 'provider', label: t('sharedVideos.provider'), value: v.company ?? v.providerId },
    { key: 'link', label: t('sharedVideos.link'), value: link
      ? <a href={link} target="_blank" rel="noopener noreferrer nofollow" className={linkClass}
        data-testid="shared-video-watch">{t('sharedVideos.watch')}</a>
      : <span className="break-all">{v.url}</span> },
    { key: 'duration', label: t('sharedVideos.duration'), value: duration(v.durationSec) },
    { key: 'vertical', label: t('sharedVideos.vertical'),
      value: t(`sharedVideos.orientation.${v.vertical ? 'vertical' : 'horizontal'}`) },
    { key: 'topics', label: t('sharedVideos.topics'), value: v.topics.join(', ') || notSet },
    ...(v.sharedAt ? [{ key: 'sharedAt', label: t('sharedVideos.sharedAt'), value: when(v.sharedAt) }] : []),
    ...(v.approvedAt ? [{ key: 'approvedAt', label: t('sharedVideos.approvedAt'), value: when(v.approvedAt) }] : []),
    ...(v.rejectReason ? [{ key: 'rejectReason', label: t('sharedVideos.rejectReason'), value: v.rejectReason }] : []),
    ...(v.withdrawnAt ? [{ key: 'withdrawnAt', label: t('sharedVideos.withdrawnAt'), value: when(v.withdrawnAt) }] : []),
    ...(v.takenDownAt ? [
      { key: 'takenDownAt', label: t('sharedVideos.takenDownAt'), value: when(v.takenDownAt) },
      { key: 'takedownReason', label: t('sharedVideos.takedownReason'),
        value: <span className="whitespace-pre-wrap">{v.takedownReason}</span> },
      { key: 'takenDownBy', label: t('sharedVideos.takenDownBy'), value: v.takedownOperatorId
        ? t('sharedVideos.takenDownByOperator') : t('sharedVideos.takenDownByPlatform') },
    ] : []),
  ]
  const pending = v.status === 'PENDING' && !!v.approvalRequestId
  let decision
  if (after.decided && (pending || after.levelOnly)) {
    decision = <AfterDecision after={after} onRetry={() => {
      after.retry()
      void video.refetch()
    }} />
  } else if (!pending) {
    decision = <p data-testid="shared-video-not-pending">
      {t('sharedVideos.notPending', { status: t(`sharedVideos.status.${v.status}`) })}</p>
  } else if (!can(PERMISSION.reviewPublication)) {
    decision = <p className="text-muted-foreground text-sm">{t('sharedVideos.notAllowed')}</p>
  } else {
    decision = <ApprovalPanel requestId={v.approvalRequestId!} onDecided={(status) => {
      after.decide(status)
      void video.refetch()
      void queryClient.invalidateQueries({ queryKey: QUEUE.sharedVideos })
    }} />
  }
  const canTakeDown = can(PERMISSION.sharedVideoWrite) && takeable(v.status)
  return (
    <div className="flex flex-col gap-4" data-testid="shared-video-page" data-status={v.status}>
      <div>{back}</div>
      <PageHeader title={v.titles.en ?? v.sharedVideoId} className="pb-0" description={
        <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <StatusBadge code={v.status} label={t(`sharedVideos.status.${v.status}`)} testId="shared-video-status" />
          <span className="text-muted-foreground text-sm" data-testid="shared-video-uses">
            {t('sharedVideos.uses', { count: v.usesRunning })}</span>
        </span>
      } />
      <Section title={t('sharedVideos.content')} testId="shared-video-content">
        <DescriptionList items={items} columns={2} />
      </Section>
      <Section title={t('sharedVideos.review')} testId="shared-video-review">
        <p className="text-muted-foreground text-sm">{t('sharedVideos.reviewHint')}</p>
        {decision}
      </Section>
      <UsesSection sharedVideoId={v.sharedVideoId} />
      {canTakeDown && (
        <Section title={t('sharedVideos.takedown')} testId="shared-video-takedown">
          <p className="text-muted-foreground text-sm">{t('sharedVideos.takedownHint')}</p>
          <div>
            <Button variant="destructive" asChild>
              <Link to={`${paths.process(SHARED_VIDEO_PROCESS.takedown, SHARED_VIDEO_PROCESS.version)}?${
                new URLSearchParams({ sharedVideoId: v.sharedVideoId })}`} data-testid="shared-video-takedown-link">
                {t('sharedVideos.takedown')}</Link>
            </Button>
          </div>
        </Section>
      )}
    </div>
  )
}

/** F-C3: the publications that went on with the video (`qb.admin.shared-video-uses`). */
function UsesSection({ sharedVideoId }: { sharedVideoId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const statusLabel = useLabels(DICTIONARY.publicationStatus)
  const reasonLabel = useLabels(DICTIONARY.publicationEndReason)
  const uses = useQuery({
    queryKey: [...QUEUE.sharedVideos, 'uses', sharedVideoId],
    queryFn: () => readSharedVideoUses(sharedVideoId),
  })
  return (
    <Section title={t('sharedVideos.usesTitle')} testId="shared-video-uses-list">
      <p className="text-muted-foreground text-sm">{t('sharedVideos.usesHint')}</p>
      {uses.isPending && <PageState kind="loading" />}
      {uses.isError && <LoadFailed error={uses.error} onRetry={() => void uses.refetch()} />}
      {uses.data && uses.data.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="shared-video-uses-none">{t('sharedVideos.usesNone')}</p>
      )}
      {uses.data && uses.data.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm" aria-label={t('sharedVideos.usesTable')}>
            <thead>
              <tr className="text-muted-foreground border-b text-left">
                <th scope="col" className="py-2 pr-3 font-medium">{t('sharedVideos.usesColumns.name')}</th>
                <th scope="col" className="py-2 pr-3 font-medium">{t('sharedVideos.usesColumns.sponsor')}</th>
                <th scope="col" className="py-2 pr-3 font-medium">{t('sharedVideos.usesColumns.status')}</th>
                <th scope="col" className="py-2 font-medium">{t('sharedVideos.usesColumns.ended')}</th>
              </tr>
            </thead>
            <tbody>
              {uses.data.map((use) => (
                <tr key={use.publicationId} className="border-b last:border-0" data-testid="shared-video-use"
                  data-id={use.publicationId} data-status={use.status} data-end-reason={use.endReason ?? ''}>
                  <td className="py-2 pr-3">
                    <Link to={PATH.publication(use.publicationId)} className={linkClass}
                      data-testid="shared-video-use-link">{use.name || use.publicationId}</Link>
                  </td>
                  <td className="py-2 pr-3"><PublisherLink kind={use.publisherKind} id={use.publisherId} /></td>
                  <td className="py-2 pr-3">
                    <StatusBadge code={use.status} label={statusLabel(use.status)} />
                  </td>
                  <td className="py-2" data-testid="shared-video-use-ended">
                    {use.endReason ? t('sharedVideos.usesEnded', { reason: reasonLabel(use.endReason),
                      at: use.endedAt ? when(use.endedAt) : '' }) : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  )
}
