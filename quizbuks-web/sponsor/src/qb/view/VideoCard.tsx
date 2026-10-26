import { Trophy } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { formatKudos } from '../kudos'
import type { FeedCard } from '../play'
import { Link } from '../router'
import { QBVIEW } from './messages'
import { VideoPlayer } from './VideoPlayer'

/**
 * A home's card whose quiz is answered by watching a video (Q19): the video's own player (it plays when the viewer
 * presses its button, never by itself), what answering earns and, in the taker app, the start (`action`). The name
 * leads to the publication's page when `href` is given (the console's preview leads nowhere).
 */
export function VideoCardView({ card, player, href, action }: { card: FeedCard; player: string; href: string | null;
  action?: ReactNode }) {
  const { t } = useTranslation(QBVIEW)
  const hint = `feed-video-hint-${card.publicationId}`
  return (
    <li className="bg-card col-span-2 flex flex-col gap-2 border p-2 shadow-sm" data-testid="feed-video-card">
      <VideoPlayer player={player} title={t('feed.videoTitle', { name: card.name })} />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          {href
            ? <Link to={href} className="truncate font-medium underline-offset-4 hover:underline" data-testid="feed-card-name">
              {card.name}
            </Link>
            : <span className="truncate font-medium" data-testid="feed-card-name">{card.name}</span>}
          <span id={hint} className="text-primary flex items-center gap-1 text-sm font-semibold"
            data-testid="feed-video-reward">
            <Trophy aria-hidden className="size-3.5" />
            {card.kind === 'SURVEY' ? t('feed.videoSurveyReward', { amount: formatKudos(card.maxReward) })
              : t('feed.videoReward', { amount: formatKudos(card.maxReward) })}
          </span>
        </div>
        {action}
      </div>
    </li>
  )
}
