import { Banknote, Coins, Gift, Ticket, Trophy, Users, type LucideIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { CoverPlaceholder } from '../Illustrations'
import { formatKudos } from '../kudos'
import type { FeedCard, RewardMethod } from '../play'
import { Link } from '../router'
import { LazyImage } from './LazyImage'
import { QBVIEW } from './messages'

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
 * One card of the picture grid (Q34; U-10): the cover fills the whole card (a drawn placeholder without one), the
 * reward and the way it is paid sit on its top, the name, the sponsor and how many answered on a dark band at its
 * foot, so the feed reads as pictures.
 */
export function FeedCardView({ card, href }: { card: FeedCard; href: string | null }) {
  const { t } = useTranslation(QBVIEW)
  const sponsor = card.sponsorCompany ?? card.sponsorName
  const MethodIcon = REWARD_METHOD_ICONS[card.rewardMethod]
  const className = 'group bg-card focus-visible:ring-ring/50 relative block aspect-[4/5] overflow-hidden border shadow-sm outline-none transition-shadow hover:shadow-md focus-visible:ring-[3px]'
  const content = (
    <>
        {card.cover
          ? <LazyImage fileId={card.cover} variant="w320" alt=""
            className="absolute inset-0 size-full transition-transform duration-300 group-hover:scale-105" />
          : <CoverPlaceholder seed={card.publicationId} className="absolute inset-0 size-full"
            data-testid="feed-card-placeholder" />}
        <span className="absolute inset-x-0 top-0 flex items-start justify-between gap-1 p-1.5">
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
        <span className="from-qb-hero-from via-qb-hero-from/85 text-qb-hero-foreground absolute inset-x-0 bottom-0 flex flex-col gap-0.5 bg-linear-to-t to-transparent px-2 pt-8 pb-2">
          <span className="line-clamp-2 text-sm leading-tight font-semibold" data-testid="feed-card-name">{card.name}</span>
          <span className="text-qb-hero-muted flex min-w-0 items-center gap-2 text-[11px]">
            {sponsor && (
              <span className="flex min-w-0 items-center gap-1">
                {card.sponsorAvatar && (
                  <LazyImage fileId={card.sponsorAvatar} variant="w128" alt="" className="size-3.5 shrink-0 rounded-full" />
                )}
                <span className="truncate">{sponsor}</span>
              </span>
            )}
            <span className="ml-auto flex shrink-0 items-center gap-0.5" data-testid="feed-card-completions">
              <Users aria-hidden className="size-3" />
              <span aria-hidden>{card.completions}</span>
              <span className="sr-only">{t('feed.completions', { count: card.completions })}</span>
            </span>
          </span>
        </span>
    </>
  )
  // The preview (Q34b) draws the card where it leads nowhere.
  return (
    <li>
      {href
        ? <Link to={href} data-testid="feed-card" className={className}>{content}</Link>
        : <div data-testid="feed-card" className={className}>{content}</div>}
    </li>
  )
}

