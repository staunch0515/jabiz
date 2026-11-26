import { useAuth } from '@jabiz/client'
import { Button } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { queueReport } from '../exposure/reporter'
import { APP } from '../messages'
import { EntryFeeStart, minutesUntil } from '../pages/PublicationPage'
import { playProblemText } from '../play/busy'
import { useStartAttempt } from '../play/useStartAttempt'
import type { FeedCard } from '../qb/play'
import { QB } from '../qb/messages'
import { prefersStillVideo } from '../qb/video'
import { satisfied, shortfallSec, toInput, usePlayback } from '../video/playback'

/** The video mode's buttons on black (Q113c): the main one white, the other outlined. */
export const FLOW_MAIN = 'h-9 shrink-0 rounded-full bg-white px-4 text-[15px] font-bold text-black hover:bg-white/90 disabled:opacity-60'
export const FLOW_OTHER = 'h-9 shrink-0 rounded-full border border-white/60 bg-transparent px-4 text-[15px] font-bold text-white hover:bg-white/10'

/**
 * "Start the quiz" in the video mode (Q113 §9.3: straight to answering, without the start page), as the start page's
 * own start does: the video to watch first (Q75: until then it says how much is left; what was watched here counts,
 * the record being the publication's), the entry fee asked first (Q88b), the attempt in progress continued, in the
 * language the card is shown in (Q73b). Nothing for a quiz the taker answered, or before their address is verified:
 * "Details" leads to the page that says why. The server decides (`QB_ATTEMPT_START`).
 */
export function FlowStart({ card, exposure }: { card: FeedCard
  /** F-S13: whether this taker's going on to answer is reported (signed in, address verified). */
  exposure: boolean }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { emailVerified } = useAuth()
  const start = useStartAttempt()
  const playback = usePlayback(card.publicationId)
  const mine = card.myAttemptId
  const inProgress = !!mine && card.myStatus === 'IN_PROGRESS' && minutesUntil(card.myDeadline ?? null) > 0
  if (!emailVerified || (mine && !inProgress)) return null
  // Without the app's player (less motion asked for) nothing can be heard: the start goes with an UNKNOWN report
  // (D-Q75-6), as on the start page.
  const noPlayer = prefersStillVideo()
  const watching = !!card.watchRequired && !inProgress
  const duration = card.videoDurationSec ?? null
  const watched = !watching || noPlayer || satisfied(playback, duration)
  const report = noPlayer && !toInput(playback) ? { status: 'UNKNOWN' as const } : toInput(playback)
  const begin = () => {
    // F-S13: going on to answer another's video (statistics only): a vertical one from this mode, a horizontal one as
    // its start page would.
    if (!inProgress && exposure && card.exposed) {
      queueReport({ publicationId: card.publicationId, kind: 'GO', surface: card.videoVertical ? 'VERTICAL' : 'START' })
    }
    start.mutate({ publicationId: card.publicationId, language: inProgress ? null : card.displayLanguage ?? null,
      playback: inProgress ? null : report })
  }
  const fee = card.entryFee ?? 0
  const label = inProgress ? t('shorts.continue')
    : !watched ? (duration != null ? t('shorts.watchLeft', { count: shortfallSec(playback, duration) }) : t('shorts.watchToEnd'))
      : t('shorts.start')
  return (
    <span className="flex flex-col items-end gap-1">
      {!inProgress && fee > 0 && watched
        ? <EntryFeeStart fee={fee} disabled={start.isPending} busy={start.isPending} onStart={begin} className={FLOW_MAIN} />
        : (
          <Button className={FLOW_MAIN} disabled={start.isPending || !watched} aria-busy={start.isPending || undefined}
            data-testid="shorts-start" data-watched={watched || undefined} onClick={begin}>
            {label}
          </Button>
        )}
      <span className="text-xs text-white/80 empty:hidden" role="status" data-testid="shorts-start-busy">
        {start.busy ? t('play.busy') : ''}
      </span>
      {start.isError && (
        <span className="text-xs text-red-300" role="alert" data-testid="shorts-start-error">
          {playProblemText(start.error) || tQb('failed')}
        </span>
      )}
    </span>
  )
}
