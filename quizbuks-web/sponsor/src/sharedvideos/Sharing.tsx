import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  Checkbox,
  Combobox,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Asset } from '../assets/api'
import { formatDuration } from '../content/duration'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { TOPIC_LIMITS, topicName } from '../qb/topics'
import { isShorts } from '../qb/video'
import { useTopicList } from '../topics/TopicParts'
import {
  CATALOG_QUERY,
  MY_SHARED_QUERY,
  readMySharedVideos,
  shareVideo,
  standing,
  titleIn,
  withdrawSharedVideo,
  type MySharedVideo,
  type SharedVideoStatus,
} from './api'
import './messages'

/** The most topics a shared video has (the server's `qb.topic.max-per-quiz`, as the console shows it elsewhere). */
const MAX_TOPICS = TOPIC_LIMITS.maxPerQuiz

/** A share's state as a badge. */
export function SharedStatus({ status }: { status: SharedVideoStatus }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <Badge variant={status === 'APPROVED' ? 'default' : status === 'PENDING' ? 'secondary' : 'outline'}
      data-testid="shared-video-status" data-status={status}>{t(`sharedVideos.status.${status}`)}</Badge>
  )
}

/**
 * Sharing a video link of the library with other Sponsors (F-S12): its title in every language (the asset's name to
 * start from), its topics from the word list and whether it is vertical. The server copies the link and the duration
 * from the asset and sends it to the provider's operator for its one review.
 */
export function ShareVideoDialog({ asset, onClose, onShared }: {
  asset: Asset
  onClose: () => void
  onShared: () => void
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const { words, offered, language } = useTopicList()
  const [titles, setTitles] = useState({ titleZh: asset.name, titleJa: asset.name, titleEn: asset.name })
  const [topics, setTopics] = useState<string[]>([])
  const [vertical, setVertical] = useState(isShorts(asset.url))
  const share = useMutation({
    mutationFn: () => shareVideo({ assetId: asset.assetId, ...titles, topics, vertical }),
    onSuccess: async (out) => {
      notify.success(out.status === 'APPROVED' ? t('sharedVideos.sharedApproved') : t('sharedVideos.shared'))
      await Promise.all([MY_SHARED_QUERY, CATALOG_QUERY].map((queryKey) => queryClient.invalidateQueries({ queryKey })))
      onShared()
    },
  })
  const name = (code: string) => topicName(code, words, language)
  const full = topics.length >= MAX_TOPICS
  return (
    <Dialog open onOpenChange={(open) => !open && !share.isPending && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg" data-testid="share-video-dialog">
        <DialogHeader>
          <DialogTitle>{t('sharedVideos.dialogTitle')}</DialogTitle>
          <DialogDescription>{t('sharedVideos.dialogIntro')}</DialogDescription>
        </DialogHeader>
        <form className="flex flex-col gap-4" onSubmit={(event) => {
          event.preventDefault()
          share.mutate()
        }}>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            <dt className="text-muted-foreground">{t('sharedVideos.video')}</dt>
            <dd className="break-all" data-testid="share-video-url">{asset.url}</dd>
            <dt className="text-muted-foreground">{t('sharedVideos.duration')}</dt>
            <dd>{asset.durationSec != null ? formatDuration(asset.durationSec) : '—'}</dd>
          </dl>
          {(['titleZh', 'titleJa', 'titleEn'] as const).map((field) => (
            <div key={field} className="flex flex-col gap-1.5">
              <Label htmlFor={`${id}-${field}`}>{t(`sharedVideos.${field}`)}</Label>
              <Input id={`${id}-${field}`} value={titles[field]} maxLength={100} required autoComplete="off"
                aria-describedby={`${id}-title-hint`} data-testid={`share-video-${field}`}
                onChange={(event) => setTitles((current) => ({ ...current, [field]: event.target.value }))} />
            </div>
          ))}
          <p id={`${id}-title-hint`} className="text-muted-foreground -mt-2 text-sm">{t('sharedVideos.titleHint')}</p>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('sharedVideos.topics')}</legend>
            <p className="text-muted-foreground text-sm">{t('sharedVideos.topicsHint')}</p>
            {topics.length > 0 && (
              <ul className="flex flex-wrap gap-2" aria-label={t('sharedVideos.topics')}>
                {topics.map((code) => (
                  <li key={code} data-testid={`share-video-topic-${code}`}>
                    <Badge variant="secondary" className="gap-1 py-1 pr-1">
                      {name(code)}
                      <Button type="button" variant="ghost" size="icon-sm" className="size-6"
                        aria-label={t('sharedVideos.topicRemove', { name: name(code) })}
                        onClick={() => setTopics((current) => current.filter((each) => each !== code))}>
                        <X aria-hidden className="size-3.5" />
                      </Button>
                    </Badge>
                  </li>
                ))}
              </ul>
            )}
            <Label htmlFor={`${id}-topic`}>{t('sharedVideos.topicAdd')}</Label>
            <Combobox id={`${id}-topic`} value={null} disabled={full}
              onChange={(code) => { if (code && !topics.includes(code)) setTopics((current) => [...current, code]) }}
              options={offered.filter((topic) => !topics.includes(topic.topicId))
                .map((topic) => ({ value: topic.topicId, label: name(topic.topicId) }))}
              placeholder={t('sharedVideos.topicPlaceholder')} searchPlaceholder={t('sharedVideos.topicSearch')}
              emptyText={t('sharedVideos.topicNone')} className="w-full" data-testid="share-video-topic" />
          </fieldset>
          <div className="flex items-start gap-2">
            <Checkbox id={`${id}-vertical`} checked={vertical} data-testid="share-video-vertical"
              aria-describedby={`${id}-vertical-hint`} onCheckedChange={(checked) => setVertical(checked === true)} />
            <div className="flex flex-col gap-0.5">
              <Label htmlFor={`${id}-vertical`}>{t('sharedVideos.vertical')}</Label>
              <p id={`${id}-vertical-hint`} className="text-muted-foreground text-sm">{t('sharedVideos.verticalHint')}</p>
            </div>
          </div>
          {share.isError && (
            <p className="text-destructive text-sm" role="alert" data-testid="share-video-error">
              {problemText(share.error)}</p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={share.isPending} onClick={onClose}>
              {t('sharedVideos.cancel')}</Button>
            <Button type="submit" disabled={share.isPending} data-testid="share-video-submit">
              {t('sharedVideos.submit')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** What became of a share, as the provider reads it. */
function Outcome({ video }: { video: MySharedVideo }) {
  const { t } = useTranslation(SPONSOR)
  if (video.status === 'REJECTED') {
    return <p className="text-sm whitespace-pre-wrap" data-testid="shared-video-reason">
      {video.rejectReason ? t('sharedVideos.sentBack', { reason: video.rejectReason }) : t('sharedVideos.sentBackNoReason')}
    </p>
  }
  if (video.status === 'TAKEN_DOWN') {
    return <p className="text-destructive text-sm whitespace-pre-wrap" data-testid="shared-video-reason">
      {t('sharedVideos.takenDown', { reason: video.takedownReason ?? '' })}</p>
  }
  return null
}

/**
 * The videos the Sponsor shares (F-S12): each with its review's result (the reason when sent back or taken down), how
 * many publications use it now, and withdrawing it (new publications no longer choose it; those submitted keep it).
 */
export function MySharedVideos() {
  const { t, i18n } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const { words } = useTopicList()
  const mine = useQuery({ queryKey: MY_SHARED_QUERY, queryFn: readMySharedVideos })
  const withdraw = useMutation({
    mutationFn: (video: MySharedVideo) => withdrawSharedVideo(video.sharedVideoId),
    onSuccess: async () => {
      notify.success(t('sharedVideos.withdrawn'))
      await Promise.all([MY_SHARED_QUERY, CATALOG_QUERY].map((queryKey) => queryClient.invalidateQueries({ queryKey })))
    },
    onError: (error) => notify.error(problemText(error)),
  })
  return (
    <section className="flex flex-col gap-3" aria-labelledby="my-shared-videos-title" data-testid="my-shared-videos">
      <h2 id="my-shared-videos-title" className="text-lg font-semibold">{t('sharedVideos.mine')}</h2>
      <p className="text-muted-foreground text-sm">{t('sharedVideos.mineHint')}</p>
      {mine.isError && <p className="text-destructive text-sm" role="alert">{problemText(mine.error)}</p>}
      {mine.data && mine.data.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="my-shared-videos-empty">{t('sharedVideos.mineEmpty')}</p>
      )}
      {mine.data && mine.data.length > 0 && (
        <ul className="flex flex-col gap-2">
          {mine.data.map((video) => {
            const title = titleIn(video.titles, i18n.language)
            return (
              <li key={video.sharedVideoId} className="flex flex-col gap-1 rounded-lg border p-3"
                data-testid="my-shared-video" data-id={video.sharedVideoId}>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{title}</span>
                  <SharedStatus status={video.status} />
                  <span className="text-muted-foreground text-sm">
                    {t(`sharedVideos.orientation.${video.vertical ? 'vertical' : 'horizontal'}`)}
                    {' · '}{formatDuration(video.durationSec)}
                    {video.sharedAt ? ` · ${formatDateTime(video.sharedAt)}` : ''}
                  </span>
                </div>
                {video.topics.length > 0 && (
                  <p className="text-muted-foreground text-sm">
                    {video.topics.map((code) => topicName(code, words, i18n.language)).join(', ')}</p>
                )}
                <p className="text-sm" data-testid="shared-video-uses">
                  {t('sharedVideos.uses', { count: video.usesRunning })}</p>
                <Outcome video={video} />
                {standing(video.status) && (
                  <div>
                    <ConfirmDialog title={t('sharedVideos.withdrawTitle')} description={t('sharedVideos.withdrawText')}
                      confirmLabel={t('sharedVideos.withdraw')} cancelLabel={t('sharedVideos.cancel')}
                      onConfirm={() => withdraw.mutate(video)}
                      trigger={<Button variant="outline" size="sm" disabled={withdraw.isPending}
                        aria-label={t('sharedVideos.withdrawNamed', { name: title })}
                        data-testid="shared-video-withdraw">{t('sharedVideos.withdraw')}</Button>} />
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
