import { cn } from '@jabiz/ui'
import { Banknote, CircleCheck, Clock, Coins, Gift, Play, Share, Ticket, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { CoverPlaceholder } from '../Illustrations'
import { formatKudos } from '../kudos'
import type { FeedCard, RewardMethod } from '../play'
import { sponsorPagePath } from '../page'
import { Link } from '../router'
import { LazyImage } from './LazyImage'
import { QBVIEW } from './messages'
import { VideoPlayer } from './VideoPlayer'
import { useVideoFailed } from '../videoFailures'
import { postAge } from './postAge'

/** The icon of each way a reward is paid (Q34): on the cards, the quiz's page and the home's reward menu. */
export const REWARD_METHOD_ICONS: Record<RewardMethod, LucideIcon> = {
  PLATFORM: Coins,
  SPONSOR_CASH: Banknote,
  OFFLINE_REDEEM: Gift,
  DIGITAL_PRIZE: Ticket,
}

/** What passing earns (a survey: what answering earns), in the card's line of small facts (Q66). */
export function RewardText({ card, className }: { card: FeedCard
  /** The look where the default is not readable (Q113c: the video mode's black). */
  className?: string }) {
  const { t } = useTranslation(QBVIEW)
  const reward = card.kind === 'SURVEY' ? t('feed.surveyReward', { amount: formatKudos(card.maxReward) })
    : t('feed.upTo', { amount: formatKudos(card.maxReward) })
  // Q88b (D-Q88-16): what starting it costs, after what it pays.
  const fee = card.entryFee ? t('feed.entryFee', { amount: formatKudos(card.entryFee) }) : null
  return (
    <span className={cn('text-foreground font-medium', className)} data-testid="feed-card-reward"
      data-entry-fee={card.entryFee || undefined}>
      {fee ? `${reward} · ${fee}` : reward}
    </span>
  )
}

/** A video's length as a player shows it (m:ss, or h:mm:ss); null when it is not known. */
export function videoLength(seconds: number | null | undefined): string | null {
  if (seconds == null || !Number.isFinite(seconds) || seconds <= 0) return null
  const whole = Math.round(seconds)
  const h = Math.floor(whole / 3600)
  const m = Math.floor((whole % 3600) / 60)
  const sec = String(whole % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/**
 * "3d", "5h", "Nov 2": the post's age in the interface's language (`postAge`); also the time of a row of the app's
 * other lists (Q113d-1: answered, notifications), with the full time as its title.
 */
export function PostAgeText({ startAt, at, testId = 'feed-card-age', title }: { startAt: string; at: string
  testId?: string
  /** The full time, for whoever points at it. */
  title?: string }) {
  const { t, i18n } = useTranslation(QBVIEW)
  const age = postAge(startAt, at)
  if (!age) return null
  const text = 'unit' in age ? t(`feed.age.${age.unit}`, { count: age.count })
    : new Intl.DateTimeFormat(i18n.language, { month: 'short', day: 'numeric',
      ...(age.withYear ? { year: 'numeric' } : {}) }).format(age.date)
  return <time dateTime={startAt} title={title} className="shrink-0" data-testid={testId}>{text}</time>
}

/**
 * One post of the home (Q113b-1; Q113 §9.1 "条目" and §9.3, as x.com's timeline): the sponsor's picture (40 px) at
 * the left, 8 px from the rest; the first line, 20 px high and level with the picture's top, the sponsor's name, the
 * post's age and `note` (the taker's own progress), with `menu` (F-S8: blocking) floating at its right end; then the
 * name in bold and the beginning of the introduction; the picture 12 px under it at 16:9 with 16 px corners, its
 * video's length in a corner (a play mark when it has one, its player instead while `playing`: the home plays the
 * post in the middle of the screen, Q65); last a line of facts 12 px under it: what passing earns in the reward's
 * colour with the way it is paid, how many answered, the time limit and `share`. Three places to tap (§9.3): the
 * sponsor's picture and name to their page; the name, the introduction and the picture to the quiz (`href`; the
 * picture goes to the video mode once Q113c makes it). `onOpen` is told of a tap that opens it. A preview (`href`
 * null) leads nowhere. The numbers are Tailwind's steps: this is the shared folder, which the frame's `xspec.ts` is
 * not in; the geometry test measures them.
 */
export function FeedCardView({ card, href, videoHref, playing = false, note, video, onOpen, menu, at, share }: {
  card: FeedCard
  href: string | null
  /**
   * Q113c (§9.3): where the post's video leads, the full-screen video mode from this post; its picture, and a tap on
   * the video playing on the home, go there (the name and the text still go to the quiz). None: the picture goes to
   * `href`, as without a video.
   */
  videoHref?: string
  playing?: boolean; note?: ReactNode
  /** Q65: the app's own place for the playing video (its one player, given the cover it comes in beside); a plain
      player otherwise. */
  video?: (cover: ReactNode) => ReactNode
  onOpen?: () => void
  /** F-S8: the app's menu of the card (blocking), at the end of its first line; none in a preview. */
  menu?: ReactNode
  /** The home's now, for the post's age; none in a preview. */
  at?: string
  /** The app's way to share the post, at the end of its facts; none in a preview. */
  share?: ReactNode }) {
  const { t } = useTranslation(QBVIEW)
  const sponsor = card.sponsorCompany ?? card.sponsorName
  const MethodIcon = REWARD_METHOD_ICONS[card.rewardMethod]
  // F-S15: a video file that failed to play: the cover, and a link to the file.
  const failed = useVideoFailed(card.video)
  const cover = card.cover
    ? <LazyImage fileId={card.cover} variant="w640" alt=""
      className="absolute inset-0 size-full transition-transform duration-300 group-hover:scale-105" />
    : <CoverPlaceholder seed={card.publicationId} className="absolute inset-0 size-full"
      data-testid="feed-card-placeholder" />
  const length = card.video && !failed ? videoLength(card.videoDurationSec) : null
  // §9.1: media have 16 px corners. Written out: the brand's theme still squares its radius steps (Q34, which Q113 P2
  // replaces page by page).
  // Q113 §9.3 (x.com, 2026-11-27): a vertical video keeps its shape on the home, 9:16 at most 287 px across (x.com's
  // 285 by 506), at the left; its cover and its player both fill this box, so they are one size.
  const frame = cn('bg-muted border-qb-outline relative mt-3 block w-full overflow-hidden rounded-[16px] border',
    card.videoVertical && card.video ? 'aspect-[9/16] max-w-[287px]' : 'aspect-video')
  const picture = (
    <span className={cn('group', frame)}>
      {cover}
      {card.video && !failed && (
        <span className="absolute inset-0 flex items-center justify-center" data-testid="feed-card-video-mark">
          <span className="bg-background/85 text-foreground flex size-12 items-center justify-center rounded-full shadow-md">
            <Play aria-hidden className="ml-0.5 size-6" />
          </span>
          <span className="sr-only">{t('feed.hasVideo')}</span>
        </span>
      )}
      {length && (
        <span aria-hidden className="absolute bottom-2 left-2 rounded bg-black/75 px-1 text-[13px] leading-5 text-white tabular-nums"
          data-testid="feed-card-length">{length}</span>
      )}
    </span>
  )
  // Q64, Q66: the sponsor's page, when it is published (the app's own route; the preview leads nowhere).
  const sponsorPage = href && card.sponsorHandle ? sponsorPagePath(card.sponsorHandle) : null
  const initial = (sponsor ?? card.name).trim().charAt(0).toUpperCase()
  const avatar = card.sponsorAvatar
    ? <LazyImage fileId={card.sponsorAvatar} variant="w128" alt="" className="size-10 rounded-full" />
    : <span aria-hidden className="bg-accent text-accent-foreground flex size-10 items-center justify-center rounded-full text-sm font-bold">
      {initial}
    </span>
  // The name and the introduction read as one paragraph, as a post's text; the name bold.
  const text = (
    <>
      <span className="font-bold" data-testid="feed-card-name">{card.name}</span>
      {/* An ordinary space between them, which a screen reader keeps (a wide one at the start of the span it drops). */}
      {card.introLead && <>{' '}<span data-testid="feed-card-intro">{card.introLead}</span></>}
    </>
  )
  // The text is a target of its own (§9.3): 2 px above and below make even one line 24 px tall (WCAG 2.5.8), and the
  // margin under it gives the 2 px back, so the post is laid out as with 20 px lines.
  const textClass = 'line-clamp-3 -mb-0.5 block py-0.5 text-[15px] leading-5 break-words'
  // Q99b: every site's video plays on the home the same way; YouTube's and Vimeo's in the app's one player (`video`),
  // the others in their own (their sound is their own button).
  const plays = !!card.video && !failed
  const reward = card.kind === 'SURVEY' ? t('feed.surveyReward', { amount: formatKudos(card.maxReward) })
    : t('feed.upTo', { amount: formatKudos(card.maxReward) })
  return (
    <li className="border-border flex gap-2 border-b px-4 py-3" data-testid="feed-card" data-publication={card.publicationId}
      data-video={plays || undefined} data-playing={(plays && playing) || undefined}>
      {sponsorPage
        ? <Link to={sponsorPage} className="h-fit shrink-0 rounded-full" data-testid="feed-card-avatar"
          aria-label={t('feed.sponsorPage', { name: sponsor ?? '' })}>{avatar}</Link>
        : <span className="shrink-0" data-testid="feed-card-avatar">{avatar}</span>}
      <div className="relative min-w-0 flex-1">
        <div className={cn('text-muted-foreground flex h-5 min-w-0 items-center gap-1 text-[15px] leading-5 whitespace-nowrap',
          menu && 'pr-7')} data-testid="feed-card-head">
          {sponsor && (sponsorPage
            ? <Link to={sponsorPage} data-testid="feed-card-sponsor"
              className="text-foreground min-w-0 truncate font-bold underline-offset-2 hover:underline">{sponsor}</Link>
            : <span className="text-foreground min-w-0 truncate font-bold" data-testid="feed-card-sponsor">{sponsor}</span>)}
          {at && card.startAt && (
            <>
              <span aria-hidden>·</span>
              <PostAgeText startAt={card.startAt} at={at} />
            </>
          )}
          {/* The taker's progress, when the note says any: its dot comes with it (a note of nothing leaves no dot). */}
          {note && <span className="min-w-0 truncate before:mr-1 before:content-['·'] empty:hidden">{note}</span>}
        </div>
        {menu && <div className="absolute -top-2 -right-2" data-testid="feed-card-menu">{menu}</div>}
        {href
          ? <Link to={href} data-testid="feed-card-link" onNavigate={onOpen}
            className={cn(textClass, 'focus-visible:ring-ring/50 rounded-sm outline-none focus-visible:ring-[3px]')}>{text}</Link>
          : <span className={textClass}>{text}</span>}
        {plays && playing
          // The cover stays still under the player, which comes in over it once it has its first frame (the stage).
          ? <span className={frame} data-testid="feed-card-playing">
            <span className="absolute inset-0 block" aria-hidden data-testid="feed-card-cover">{cover}</span>
            {video ? video(cover) : <VideoPlayer player={card.video!} autoplay title={t('feed.videoTitle', { name: card.name })}
              className="bg-muted absolute inset-0 size-full overflow-hidden" />}
            {/* Q113c: another site's player takes taps of its own (it is in a frame): a tap anywhere on it opens the
                video mode, whose controls it has (x.com's way). The app's own player (`video`) has the stage's button
                for that (`video/stage.tsx`). */}
            {videoHref && plays && !video && (
              <Link to={videoHref} onNavigate={onOpen} data-testid="feed-card-video-open"
                aria-label={t('feed.openVideoMode', { name: card.name })}
                className="focus-visible:ring-ring absolute inset-0 z-10 block rounded-[16px] outline-none focus-visible:ring-[3px] focus-visible:ring-inset">
                <span className="sr-only">{t('feed.openVideoMode', { name: card.name })}</span>
              </Link>
            )}
          </span>
          : href
            ? <Link to={videoHref && plays ? videoHref : href} data-testid="feed-card-picture" onNavigate={onOpen}
              aria-label={videoHref && plays ? t('feed.openVideoMode', { name: card.name }) : card.name}
              className="block rounded-[16px]">{picture}</Link>
            : picture}
        <div className="text-muted-foreground mt-3 flex h-5 items-center justify-between gap-2 text-[13px] leading-5"
          data-testid="feed-card-facts">
          <span className="text-qb-reward-foreground flex min-w-0 items-center gap-1 font-bold" data-testid="feed-card-reward"
            data-entry-fee={card.entryFee || undefined}>
            <span className="contents" data-testid="feed-card-method" data-method={card.rewardMethod}>
              <MethodIcon aria-hidden className="size-[19px] shrink-0" />
              <span className="sr-only">{t(`methods.${card.rewardMethod}Short`)}</span>
            </span>
            {card.kind === 'SURVEY' && <span className="sr-only" data-testid="feed-card-survey">{t('feed.survey')}</span>}
            <span className="truncate">
              {card.entryFee ? `${reward} · ${t('feed.entryFee', { amount: formatKudos(card.entryFee) })}` : reward}
            </span>
          </span>
          <span className="flex shrink-0 items-center gap-1" data-testid="feed-card-completions">
            <CircleCheck aria-hidden className="size-[19px]" />
            <span aria-hidden className="tabular-nums">{card.completions.toLocaleString()}</span>
            <span className="sr-only">{t('feed.answered', { count: card.completions })}</span>
          </span>
          <span className="flex shrink-0 items-center gap-1" data-testid="feed-card-time-limit">
            <Clock aria-hidden className="size-[19px]" />
            <span aria-hidden>{t('detail.minutes', { count: Math.ceil(card.timeLimitSec / 60) })}</span>
            <span className="sr-only">{t('feed.timeLimit', { time: t('detail.minutes', { count: Math.ceil(card.timeLimitSec / 60) }) })}</span>
          </span>
          {share ? <span className="-my-2 -mr-2 shrink-0" data-testid="feed-card-share">{share}</span>
            : <Share aria-hidden className="size-[19px] shrink-0 opacity-60" />}
        </div>
      </div>
    </li>
  )
}
