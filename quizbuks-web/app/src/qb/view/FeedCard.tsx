import { Banknote, Coins, Gift, Play, Ticket, Trophy, type LucideIcon } from 'lucide-react'
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

/** The icon of each way a reward is paid (Q34): on the cards, the quiz's page and the home's reward menu. */
export const REWARD_METHOD_ICONS: Record<RewardMethod, LucideIcon> = {
  PLATFORM: Coins,
  SPONSOR_CASH: Banknote,
  OFFLINE_REDEEM: Gift,
  DIGITAL_PRIZE: Ticket,
}

/** The reward on a card's picture: what passing earns (a survey: what answering earns). */
export function RewardTag({ card }: { card: FeedCard }) {
  const { t } = useTranslation(QBVIEW)
  return (
    <span className="bg-primary text-primary-foreground inline-flex items-center gap-1 px-2 py-0.5 text-xs font-semibold shadow-sm"
      data-testid="feed-card-reward">
      <Trophy aria-hidden className="size-3" />
      {card.kind === 'SURVEY' ? t('feed.surveyReward', { amount: formatKudos(card.maxReward) })
        : t('feed.upTo', { amount: formatKudos(card.maxReward) })}
    </span>
  )
}

/**
 * One card of the home (Q63, as a video site's home): the picture across the screen at 16:9 (a drawn placeholder
 * without one), with the reward and the way it is paid on its corners; under it the sponsor's picture, the name in up
 * to two lines and the sponsor and how many answered. A publication with a video (`card.video`: the one to watch to
 * answer, else the first the App plays) shows a play mark on its picture, and its player instead, started muted, while
 * `playing` (the home plays the card in the middle of the screen). `action` (the app's start, Q64) sits at the right
 * of the row under the picture (Q65).
 */
export function FeedCardView({ card, href, playing = false, action, video }: { card: FeedCard; href: string | null
  playing?: boolean; action?: ReactNode
  /** Q65: the app's own place for the playing video (its one player); a plain player otherwise. */
  video?: ReactNode }) {
  const { t } = useTranslation(QBVIEW)
  const sponsor = card.sponsorCompany ?? card.sponsorName
  const MethodIcon = REWARD_METHOD_ICONS[card.rewardMethod]
  const picture = (
    <span className="group bg-muted relative block aspect-video overflow-hidden">
      {card.cover
        ? <LazyImage fileId={card.cover} variant="w640" alt=""
          className="absolute inset-0 size-full transition-transform duration-300 group-hover:scale-105" />
        : <CoverPlaceholder seed={card.publicationId} className="absolute inset-0 size-full"
          data-testid="feed-card-placeholder" />}
      <span className="absolute inset-x-0 top-0 flex items-start justify-between gap-1 p-2">
        <span className="flex flex-col items-start gap-1">
          {card.rewardMethod !== 'PLATFORM' && (
            <span className="bg-background/90 text-foreground inline-flex items-center gap-1 px-1.5 py-0.5 text-[11px] font-medium"
              data-testid="feed-card-method" data-method={card.rewardMethod}>
              <MethodIcon aria-hidden className="size-3" />
              {t(`methods.${card.rewardMethod}Short`)}
            </span>
          )}
          {card.kind === 'SURVEY' && (
            <span className="bg-background/90 text-foreground px-1.5 py-0.5 text-[11px] font-medium"
              data-testid="feed-card-survey">{t('feed.survey')}</span>
          )}
        </span>
        <RewardTag card={card} />
      </span>
      {card.video && (
        <span className="absolute inset-0 flex items-center justify-center" data-testid="feed-card-video-mark">
          <span className="bg-background/85 text-foreground flex size-12 items-center justify-center rounded-full shadow-md">
            <Play aria-hidden className="ml-0.5 size-6" />
          </span>
          <span className="sr-only">{t('feed.hasVideo')}</span>
        </span>
      )}
    </span>
  )
  const name = <span className="line-clamp-2 leading-snug font-semibold" data-testid="feed-card-name">{card.name}</span>
  return (
    <li className="flex flex-col" data-testid="feed-card" data-publication={card.publicationId}
      data-video={card.video ? true : undefined} data-playing={playing || undefined}>
      {card.video && playing
        ? video ?? <VideoPlayer player={card.video} autoplay title={t('feed.videoTitle', { name: card.name })}
          className="bg-muted aspect-video w-full overflow-hidden" />
        : href
          ? <Link to={href} aria-label={card.name} data-testid="feed-card-picture">{picture}</Link>
          : picture}
      <div className="flex gap-3 px-1 pt-2">
        {card.sponsorAvatar
          ? <LazyImage fileId={card.sponsorAvatar} variant="w128" alt="" className="size-9 shrink-0 rounded-full" />
          : <span aria-hidden className="bg-accent text-accent-foreground flex size-9 shrink-0 items-center justify-center rounded-full text-sm font-semibold">
            {(sponsor ?? card.name).trim().charAt(0).toUpperCase()}
          </span>}
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          {href
            ? <Link to={href} data-testid="feed-card-link" className="hover:underline focus-visible:ring-ring/50 underline-offset-4 outline-none focus-visible:ring-[3px]">{name}</Link>
            : name}
          <span className="text-muted-foreground flex min-w-0 flex-wrap items-center gap-x-1.5 text-xs">
            {sponsor && (
              <span className="flex min-w-0 items-center gap-1">
                {/* Q64: the sponsor's page, when it is published (the app's own route; the preview leads nowhere). */}
                {href && card.sponsorHandle
                  ? <Link to={sponsorPagePath(card.sponsorHandle)} data-testid="feed-card-sponsor"
                    className="truncate underline-offset-2 hover:underline">{sponsor}</Link>
                  : <span className="truncate" data-testid="feed-card-sponsor">{sponsor}</span>}
              </span>
            )}
            {sponsor && <span aria-hidden>·</span>}
            <span data-testid="feed-card-completions">{t('feed.completions', { count: card.completions })}</span>
          </span>
          {/* The player covers the picture and its reward: while it plays, the reward is said here. */}
          {card.video && playing && <span className="self-start"><RewardTag card={card} /></span>}
        </div>
        {/* Q65: the app's start at the right of the row, where a video site puts its menu. */}
        {action && <div className="shrink-0">{action}</div>}
      </div>
    </li>
  )
}
