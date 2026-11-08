import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { CoverPlaceholder } from '../qb/Illustrations'
import type { FeedCard } from '../qb/play'
import { hrefOf } from '../qb/router'
import { LazyImage } from '../qb/view/LazyImage'
import { RewardText } from '../qb/view/FeedCard'

/**
 * F-S10: one shelf of vertical short videos among the home's cards, like YouTube's Shorts: a heading and a row of
 * small 9:16 cards that scrolls sideways. A card shows only its cover (cropped upright), its name and its reward, never
 * a player (the home keeps its one player, Q65); a tap opens the full-screen vertical mode on it (`onOpen`), whose
 * address `hrefOf` gives (a link, so that it opens in a new tab as well).
 */
export function ShortsShelf({ items, hrefOf: addressOf, onOpen }: {
  items: readonly FeedCard[]
  hrefOf: (card: FeedCard) => string
  onOpen: (card: FeedCard) => void
}) {
  const { t } = useTranslation(APP)
  const title = useId()
  return (
    <li className="flex flex-col gap-2 py-1" data-testid="shorts-shelf" aria-labelledby={title}>
      <h2 id={title} className="px-4 text-base font-semibold sm:px-1">{t('shorts.title')}</h2>
      <ul className="flex snap-x gap-3 overflow-x-auto px-4 pb-2 sm:px-1" data-testid="shorts-shelf-items">
        {items.map((card) => (
          <li key={card.publicationId} className="w-36 shrink-0 snap-start sm:w-40" data-testid="shorts-card"
            data-publication={card.publicationId}>
            <a href={hrefOf(addressOf(card))} data-testid="shorts-card-open"
              aria-label={t('shorts.open', { name: card.name })}
              className="focus-visible:ring-ring/50 flex flex-col gap-1.5 rounded-md outline-none focus-visible:ring-[3px]"
              onClick={(event) => {
                if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
                event.preventDefault()
                onOpen(card)
              }}>
              <span className="bg-muted relative block aspect-[9/16] overflow-hidden rounded-md">
                {card.cover
                  ? <LazyImage fileId={card.cover} variant="w640" alt="" className="absolute inset-0 size-full object-cover" />
                  : <CoverPlaceholder seed={card.publicationId} className="absolute inset-0 size-full" />}
              </span>
              <span className="line-clamp-2 text-sm leading-snug font-medium" data-testid="shorts-card-name">{card.name}</span>
              <span className="text-muted-foreground text-xs"><RewardText card={card} /></span>
            </a>
          </li>
        ))}
      </ul>
    </li>
  )
}
