import { ApiError } from '@jabiz/client'
import { Button, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { playProblemText, retryWhenBusy } from '../qb/answer/busy'
import { clockOffset } from '../qb/answer/countdown'
import { Player, refusedWith, type AnswerFlow } from '../qb/answer/Player'
import { CODE } from '../qb/ids'
import { QB } from '../qb/messages'
import { Link } from '../qb/router'
import { CHALLENGES_PATH, challengeResultPath, saveChallenge, startChallenge, submitChallenge } from './api'
import './messages'

/** The Sponsor's answering of a challenge (F-S4c): the shared player with the Sponsor's processes. */
const SPONSOR_FLOW: AnswerFlow = {
  save: saveChallenge,
  submit: submitChallenge,
  resultPath: challengeResultPath,
}

/**
 * Answers a challenge (F-S4c): starts the Sponsor's attempt, or continues the one in progress
 * (`QB_SPONSOR_ATTEMPT_START`, retried with the same key while the quiz is busy), then plays it with the shared player;
 * an attempt already finished, or one whose time ran out, goes back to the challenges, where its result is.
 */
export function ChallengePlayPage({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const [busy, setBusy] = useState(false)
  const [key] = useState(() => crypto.randomUUID())
  const started = useQuery({
    queryKey: ['qb', 'sponsor-challenge-start', publicationId],
    queryFn: async ({ signal }) => {
      const output = await retryWhenBusy(() => startChallenge(publicationId, key), { onBusy: setBusy, signal })
      return { output, offset: clockOffset(output.serverTime) }
    },
    staleTime: Infinity, gcTime: 0, retry: false,
  })
  const back = <Button asChild variant="outline"><Link to={CHALLENGES_PATH}>{t('challenges.back')}</Link></Button>
  if (started.isPending) {
    return <PageState kind="loading" title={busy ? tQb('answer.busy') : undefined} data-testid="challenge-loading" />
  }
  if (started.isError) {
    const error = started.error
    if (refusedWith(error, CODE.attemptAlreadyTaken, CODE.attemptExpired)) {
      return <PageState kind="warning" titleAs="h1" title={t('challenges.taken')} action={back}
        data-testid="challenge-taken" />
    }
    if (error instanceof ApiError && error.status === 404) {
      return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} action={back} />
    }
    return <PageState kind="error" description={playProblemText(error)} data-testid="challenge-start-error"
      action={<Button variant="outline" onClick={() => void started.refetch()}>{tQb('retry')}</Button>} />
  }
  return <Player key={started.data.output.attemptId} started={started.data} flow={SPONSOR_FLOW} />
}
