import { ApiError } from '@jabiz/client'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { CODE } from '../qb/ids'
import { startAttempt } from '../qb/play'
import { navigate } from '../qb/router'
import { retryWhenBusy } from './busy'
import { handOver } from './storage'

/** Whether a refusal carries one of the codes. */
export function refusedWith(error: unknown, ...codes: string[]): boolean {
  return error instanceof ApiError && error.violations.some((v) => codes.includes(v.ruleCode))
}

/**
 * Starts the caller's attempt of a publication, or continues the one in progress (`QB_ATTEMPT_START`), then plays it.
 * While the quiz is too busy (503 `PROCESS_BUSY`) it tries again by itself with the same key, and `busy` says so; once
 * the page goes away it stops trying, and a start answered after that does not take the user back to the quiz.
 * Refusals are the mutation's error (the server's text); one because the caller answered meanwhile (another tab) or
 * whose time ran out reloads the pages that read the attempt, which then offer its result.
 */
export function useStartAttempt() {
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const page = useRef<AbortController | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    page.current = controller
    return () => controller.abort()
  }, [])
  const mutation = useMutation({
    mutationFn: (publicationId: string) => {
      // One key for this start and its retries. Each click is a new start: the server keeps one attempt per taker
      // and publication (its unique key) and a second start continues the one in progress.
      const key = crypto.randomUUID()
      return retryWhenBusy(() => startAttempt(publicationId, key), { onBusy: setBusy, signal: page.current?.signal })
    },
    onSuccess: (output) => {
      if (page.current?.signal.aborted) return
      handOver(output)
      navigate(`/play/${output.attemptId}`)
    },
    onError: (error) => {
      if (refusedWith(error, CODE.attemptAlreadyTaken, CODE.attemptExpired)) {
        void queryClient.invalidateQueries({ queryKey: ['qb', 'publication'] })
        void queryClient.invalidateQueries({ queryKey: ['qb', 'history'] })
      }
    },
  })
  return { ...mutation, busy }
}
