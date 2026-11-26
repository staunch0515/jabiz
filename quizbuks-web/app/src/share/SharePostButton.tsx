import { Button, notify } from '@jabiz/ui'
import { Share } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { hrefOf } from '../qb/router'
import { QBVIEW } from '../qb/view/messages'
import { sharePath } from './share'

/** The link a post shares: its share link's page (Q15, `/p/<id>`), on this site. */
export function postUrl(publicationId: string, origin: string = window.location.origin): string {
  return `${origin}${hrefOf(sharePath(publicationId))}`
}

/**
 * The share button at the end of a home post's facts (Q113b-1; Q113 §9.2: share is one of the post's few actions):
 * the device's own sheet where it has one, else the link copied (as the referral link's buttons do). Cancelling the
 * sheet is no failure.
 */
export function SharePostButton({ publicationId, name }: { publicationId: string; name: string }) {
  const { t } = useTranslation(QBVIEW)
  const share = async () => {
    const url = postUrl(publicationId)
    if (typeof navigator.share === 'function') {
      try {
        await navigator.share({ title: name, url })
      } catch {
        // Cancelled, or not possible here: nothing to say.
      }
      return
    }
    try {
      await navigator.clipboard.writeText(url)
      notify.success(t('feed.shareCopied'))
    } catch {
      notify.error(t('feed.shareFailed'))
    }
  }
  return (
    <Button type="button" variant="ghost" size="icon" className="text-muted-foreground hover:text-primary size-[34px] rounded-full"
      aria-label={t('feed.share')} data-testid="feed-card-share-button" onClick={() => void share()}>
      <Share aria-hidden className="size-[19px]" />
    </Button>
  )
}
