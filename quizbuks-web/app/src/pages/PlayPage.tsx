import { ApiError } from '@jabiz/client'
import { Button, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { clockOffset } from '../play/countdown'
import { markAutoSubmitted, storedStart, storeResult, storeStart, takeHandedOver, type Started } from '../play/storage'
import { Player, refusedWith, type AnswerFlow } from '../qb/answer/Player'
import { CODE } from '../qb/ids'
import { QB } from '../qb/messages'
import { readAttemptResult, saveAttempt, startAttempt, submitAttempt } from '../qb/play'
import { Link, Redirect } from '../qb/router'
import { playProblemText, retryWhenBusy } from '../play/busy'

export { letter, SUBMIT_GRACE_MS } from '../qb/answer/Player'

/** The Taker's answering (F-S4c: the player is shared with the Sponsor app's challenges). */
const TAKER_FLOW: AnswerFlow = {
  save: saveAttempt,
  submit: submitAttempt,
  resultPath: (attemptId) => `/result/${attemptId}`,
  onSubmitted: storeResult,
  onAutoSubmitted: markAutoSubmitted,
}

/** Where the play page goes instead of playing: the attempt's result, or nothing (it plays). */
type Resolved = { started: Started } | { result: string } | { expired: string }

/**
 * The attempt to play: handed over by the page that started it, else continued from the server (`QB_ATTEMPT_START`
 * returns the attempt in progress with its answers and current question: a reload resumes it). The publication comes
 * from this tab's copy of the start, else from the attempt's result (its state). A finished attempt goes to its result.
 */
async function resolve(attemptId: string, onBusy: (busy: boolean) => void, signal?: AbortSignal):
  Promise<Resolved> {
  const handed = takeHandedOver(attemptId)
  if (handed) return { started: handed }
  let publicationId = storedStart(attemptId)?.publicationId
  if (!publicationId) {
    const record = await readAttemptResult(attemptId)
    if (record.status !== 'IN_PROGRESS') return { result: attemptId }
    publicationId = record.publicationId
  }
  try {
    // A hot quiz may answer busy (its queue is full): try again with the same key (play/busy.ts).
    const key = crypto.randomUUID()
    const from = publicationId
    const output = await retryWhenBusy(() => startAttempt(from, key), { onBusy, signal })
    storeStart(output)
    return { started: { output, offset: clockOffset(output.serverTime) } }
  } catch (error) {
    if (refusedWith(error, CODE.attemptAlreadyTaken)) return { result: attemptId }
    if (refusedWith(error, CODE.attemptExpired)) return { expired: attemptId }
    throw error
  }
}

/**
 * One question per page (U-30 – U-32, U-41; docs/quizbuks/plans/QP-pages.md): the progress, the countdown by the
 * server's clock, the options as check boxes (the start never says which questions have several correct options, so
 * any number may be chosen; a survey's single choice as radio buttons), previous and next; answers saved as they change, the current question on every move.
 * Submitting asks first; when the time runs out the answers are submitted without asking.
 */
export function PlayPage({ attemptId }: { attemptId: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [busy, setBusy] = useState(false)
  const resolved = useQuery({ queryKey: ['qb', 'play', attemptId], queryFn: ({ signal }) => resolve(attemptId, setBusy, signal),
    staleTime: Infinity, gcTime: 0 })
  if (resolved.isPending) {
    return <PageState kind="loading" title={busy ? t('play.busy') : undefined} data-testid="play-loading" />
  }
  if (resolved.isError) {
    if (resolved.error instanceof ApiError && resolved.error.status === 404) {
      return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')}
        action={<Button asChild variant="outline"><Link to="/history">{t('nav.history')}</Link></Button>} />
    }
    return <PageState kind="error" description={playProblemText(resolved.error)}
      action={<Button variant="outline" onClick={() => void resolved.refetch()}>{tQb('retry')}</Button>} />
  }
  const data = resolved.data
  if ('result' in data) return <Redirect to={`/result/${data.result}`} />
  if ('expired' in data) {
    return (
      <PageState kind="warning" titleAs="h1" title={t('play.expiredTitle')} description={t('play.expired')}
        data-testid="play-expired"
        action={<Button asChild><Link to={`/result/${data.expired}`}>{t('play.toResult')}</Link></Button>} />
    )
  }
  return <Player key={attemptId} started={data.started} flow={TAKER_FLOW} />
}

