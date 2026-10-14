import { ApiError } from '@jabiz/client'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { CODE } from '../qb/ids'
import { startAttempt } from '../qb/play'
import { navigate } from '../qb/router'
import { handOver } from './storage'

/** Whether a refusal carries one of the codes. */
export function refusedWith(error: unknown, ...codes: string[]): boolean {
  return error instanceof ApiError && error.violations.some((v) => codes.includes(v.ruleCode))
}

/**
 * Starts the caller's attempt of a publication, or continues the one in progress (`QB_ATTEMPT_START`), then plays it.
 * Refusals are the mutation's error (the server's text); one because the caller answered meanwhile (another tab) or
 * whose time ran out reloads the pages that read the attempt, which then offer its result.
 */
export function useStartAttempt() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: startAttempt,
    onSuccess: (output) => {
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
}
