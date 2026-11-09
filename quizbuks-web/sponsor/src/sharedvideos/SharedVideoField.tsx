import {
  Alert,
  AlertDescription,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@jabiz/ui'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { Clapperboard } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { formatDuration } from '../content/duration'
import { SPONSOR } from '../messages'
import { useDebounced } from '../publishing/shared'
import { problemText } from '../qb/problems'
import { topicName } from '../qb/topics'
import { foreignVideo, mainlandSites } from '../qb/video'
import { useTopicList } from '../topics/TopicParts'
import { CATALOG_QUERY, readCatalog, readSharedVideo, sharedVideoQuery, titleIn, type SharedVideo } from './api'
import { SharedStatus } from './Sharing'
import './messages'

const ALL = '*'

/** The video a draft picked, whatever became of it (null: none, or gone). */
export function useSharedVideo(sharedVideoId: string | null) {
  return useQuery({
    queryKey: sharedVideoQuery(sharedVideoId ?? ''),
    queryFn: () => readSharedVideo(sharedVideoId!),
    enabled: sharedVideoId !== null,
    staleTime: 60_000,
  })
}

/** F-S11: whether mainland China cannot play the shared video (as the server freezes it on the card). */
export const sharedVideoForeign = (video: SharedVideo | null | undefined) =>
  !!video && foreignVideo([video.url], mainlandSites(null))

/** One shared video as a line: title, provider, orientation, length and topics. */
function VideoSummary({ video }: { video: SharedVideo }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const { words } = useTopicList()
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="font-medium" data-testid="shared-video-title">{titleIn(video.titles, i18n.language)}</span>
      <span className="text-muted-foreground text-sm">
        {t('sharedVideos.field.by', { provider: video.providerCompany ?? '—' })}
        {' · '}{t(`sharedVideos.orientation.${video.vertical ? 'vertical' : 'horizontal'}`)}
        {' · '}{formatDuration(video.durationSec)}
      </span>
      {video.topics.length > 0 && (
        <span className="text-muted-foreground text-xs">
          {video.topics.map((code) => topicName(code, words, i18n.language)).join(', ')}</span>
      )}
    </div>
  )
}

/**
 * The catalog of shared videos (F-S12): those other Sponsors share and their operator approved, searched by title or
 * Sponsor, by topic and by orientation; each with a link to watch it on its site and a button to use it.
 */
function Picker({ onPick, onClose }: { onPick: (video: SharedVideo) => void; onClose: () => void }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const id = useId()
  const { words, offered, language } = useTopicList()
  const [search, setSearch] = useState('')
  const [topic, setTopic] = useState(ALL)
  const [orientation, setOrientation] = useState(ALL)
  const q = useDebounced(search, 300)
  const filter = { q, topic: topic === ALL ? null : topic,
    vertical: orientation === ALL ? null : orientation === 'vertical' }
  const catalog = useQuery({ queryKey: [...CATALOG_QUERY, filter], queryFn: () => readCatalog(filter),
    placeholderData: keepPreviousData })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl" data-testid="shared-video-picker">
        <DialogHeader>
          <DialogTitle>{t('sharedVideos.picker.title')}</DialogTitle>
          <DialogDescription>{t('sharedVideos.picker.intro')} {t('sharedVideos.picker.ownHint')}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-wrap items-end gap-3" role="search" aria-label={t('sharedVideos.picker.search')}>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-q`}>{t('sharedVideos.picker.search')}</Label>
            <Input id={`${id}-q`} type="search" value={search} className="w-56" autoComplete="off"
              placeholder={t('sharedVideos.picker.searchPlaceholder')} data-testid="shared-video-search"
              onChange={(event) => setSearch(event.target.value)} />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-topic`}>{t('sharedVideos.picker.topic')}</Label>
            <Select value={topic} onValueChange={setTopic}>
              <SelectTrigger id={`${id}-topic`} className="w-44" data-testid="shared-video-topic"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>{t('sharedVideos.picker.allTopics')}</SelectItem>
                {offered.map((word) => (
                  <SelectItem key={word.topicId} value={word.topicId}>{topicName(word.topicId, words, language)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-orientation`}>{t('sharedVideos.picker.orientation')}</Label>
            <Select value={orientation} onValueChange={setOrientation}>
              <SelectTrigger id={`${id}-orientation`} className="w-44" data-testid="shared-video-orientation">
                <SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>{t('sharedVideos.picker.allOrientations')}</SelectItem>
                <SelectItem value="vertical">{t('sharedVideos.orientation.vertical')}</SelectItem>
                <SelectItem value="horizontal">{t('sharedVideos.orientation.horizontal')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
        {catalog.isPending && <p className="text-muted-foreground text-sm">{t('sharedVideos.picker.loading')}</p>}
        {catalog.isError && <p className="text-destructive text-sm" role="alert">{problemText(catalog.error)}</p>}
        {catalog.data && catalog.data.length === 0 && (
          <p className="text-muted-foreground text-sm" data-testid="shared-video-none">{t('sharedVideos.picker.none')}</p>
        )}
        {catalog.data && catalog.data.length > 0 && (
          <ul className="flex flex-col gap-2" aria-label={t('sharedVideos.picker.results')}>
            {catalog.data.map((video) => {
              const title = titleIn(video.titles, i18n.language)
              return (
                <li key={video.sharedVideoId} className="flex items-center justify-between gap-3 rounded-lg border p-3"
                  data-testid="shared-video-option" data-id={video.sharedVideoId}>
                  <VideoSummary video={video} />
                  <span className="flex shrink-0 gap-2">
                    <Button variant="ghost" size="sm" asChild>
                      <a href={video.url} target="_blank" rel="noopener noreferrer nofollow">
                        {t('sharedVideos.picker.preview')}</a>
                    </Button>
                    <Button size="sm" aria-label={t('sharedVideos.picker.pickNamed', { name: title })}
                      data-testid="shared-video-pick" onClick={() => onPick(video)}>
                      {t('sharedVideos.picker.pick')}</Button>
                  </span>
                </li>
              )
            })}
          </ul>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t('sharedVideos.picker.close')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/**
 * A draft publication's shared video (F-S12) in the wizard's first step: none, or one picked from the catalog. The
 * page keeps the pick and the wizard saves it with the draft (`QB_PUBLICATION_SHARED_VIDEO_SET`, after the draft's own
 * save); submitted, it is frozen. A pick no longer in the catalog (withdrawn, taken down) is said so: the submission
 * would be refused.
 */
export function SharedVideoField({ value, onChange, disabled }: {
  value: string | null
  onChange: (sharedVideoId: string | null) => void
  disabled: boolean
}) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const [picking, setPicking] = useState(false)
  const picked = useSharedVideo(value)
  const video = value !== null ? picked.data ?? null : null
  return (
    <fieldset className="flex flex-col gap-3 rounded-lg border p-4" data-testid="publication-shared-video">
      <legend className="flex items-center gap-1.5 px-1 text-sm font-medium">
        <Clapperboard aria-hidden className="size-4" />{t('sharedVideos.field.title')}</legend>
      <p className="text-muted-foreground text-sm">{t('sharedVideos.field.hint')}</p>
      {value === null && (
        <p className="text-sm" data-testid="publication-shared-video-none">{t('sharedVideos.field.none')}</p>
      )}
      {video && (
        <div className="flex flex-wrap items-center gap-2" data-testid="publication-shared-video-picked"
          data-id={video.sharedVideoId}>
          <VideoSummary video={video} />
          {video.status !== 'APPROVED' && <SharedStatus status={video.status} />}
        </div>
      )}
      {video && video.status !== 'APPROVED' && (
        <Alert variant="warning" data-testid="publication-shared-video-unavailable">
          <AlertDescription className="text-current">
            {t('sharedVideos.field.unavailable', { status: t(`sharedVideos.status.${video.status}`) })}
          </AlertDescription>
        </Alert>
      )}
      <span className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" disabled={disabled} data-testid="publication-shared-video-choose"
          onClick={() => setPicking(true)}>
          {value === null ? t('sharedVideos.field.choose') : t('sharedVideos.field.change')}</Button>
        {value !== null && (
          <Button type="button" variant="ghost" disabled={disabled} data-testid="publication-shared-video-remove"
            onClick={() => onChange(null)}>{t('sharedVideos.field.remove')}</Button>
        )}
      </span>
      {picking && <Picker onClose={() => setPicking(false)} onPick={(chosen) => {
        // Known already: shown at once, without reading it again.
        queryClient.setQueryData(sharedVideoQuery(chosen.sharedVideoId), chosen)
        onChange(chosen.sharedVideoId)
        setPicking(false)
      }} />}
    </fieldset>
  )
}
