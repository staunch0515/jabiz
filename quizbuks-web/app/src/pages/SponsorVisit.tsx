import { ChevronRight, Store } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { LazyImage } from '../qb/view/LazyImage'
import { sponsorPagePath } from '../qb/page'
import { Link } from '../qb/router'

/**
 * The way to the sponsor's own page (Q64): a row naming the sponsor that opens their published page (Q60a), where
 * their story and all their products are, on the start page and the result whether the quiz was answered or not.
 * Nothing while the sponsor has no published page.
 */
export function SponsorVisit({ handle, sponsor, avatar, products }: { handle: string | null | undefined
  sponsor: string | null; avatar: string | null; products: boolean }) {
  const { t } = useTranslation(APP)
  if (!handle || !sponsor) return null
  return (
    <Link to={sponsorPagePath(handle)} data-testid="sponsor-visit"
      className="bg-card hover:bg-accent flex items-center gap-3 rounded-lg border p-3">
      {avatar
        ? <LazyImage fileId={avatar} variant="w128" alt="" className="size-10 shrink-0 rounded-full" />
        : <Store aria-hidden className="text-primary size-6 shrink-0" />}
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-sm font-medium">{t('sponsorVisit.title', { sponsor })}</span>
        <span className="text-muted-foreground text-xs">
          {products ? t('sponsorVisit.hint') : t('sponsorVisit.products')}
        </span>
      </span>
      <ChevronRight aria-hidden className="text-muted-foreground size-4 shrink-0" />
    </Link>
  )
}
