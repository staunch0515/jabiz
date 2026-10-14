import type { StartOutput, SubmitOutput } from '../qb/play'
import { clockOffset } from './countdown'

/*
 * What the play and result pages keep in the tab (docs/quizbuks/plans/QP-pages.md, gap A-3, Q-2): the output of
 * QB_ATTEMPT_START (the questions and their options as shown, never which are correct) and of QB_ATTEMPT_SUBMIT. The
 * result page shows the questions' text from it right after finishing; later, from another tab or after the tab
 * closed, only the score and each question's right or wrong come from the server (the attempt's `result`).
 *
 * sessionStorage can be missing or refuse (private windows, full storage): every access is guarded and the pages work
 * without it, reading from the server instead.
 */

const START = 'qb.play.start.'
const RESULT = 'qb.play.result.'
const AUTO = 'qb.play.auto.'

function read<T>(key: string): T | null {
  try {
    const text = window.sessionStorage.getItem(key)
    return text ? (JSON.parse(text) as T) : null
  } catch {
    return null
  }
}

function write(key: string, value: unknown) {
  try {
    window.sessionStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Without storage the result page reads the server's record instead.
  }
}

export const storedStart = (attemptId: string) => read<StartOutput>(START + attemptId)
export const storeStart = (output: StartOutput) => write(START + output.attemptId, output)

export const storedResult = (attemptId: string) => read<SubmitOutput>(RESULT + attemptId)
export const storeResult = (output: SubmitOutput) => write(RESULT + output.attemptId, output)

/** Whether this tab submitted the attempt because its time ran out (the result page says so). */
export const autoSubmitted = (attemptId: string) => read<boolean>(AUTO + attemptId) === true
export const markAutoSubmitted = (attemptId: string) => write(AUTO + attemptId, true)

/** Forgets every attempt this tab kept (on signing out: the next person in this tab must not see them). */
export function forgetAttempts() {
  try {
    const keys: string[] = []
    for (let i = 0; i < window.sessionStorage.length; i++) {
      const key = window.sessionStorage.key(i)
      if (key && (key.startsWith(START) || key.startsWith(RESULT) || key.startsWith(AUTO))) keys.push(key)
    }
    keys.forEach((key) => window.sessionStorage.removeItem(key))
  } catch {
    // Nothing kept.
  }
  handed = null
}

/** A start's output with the server's clock offset measured when it arrived (see `countdown.ts`). */
export interface Started {
  output: StartOutput
  offset: number
}

/**
 * A start's output handed from the page that ran QB_ATTEMPT_START (detail, history) to the play page, which then
 * does not run it again. Kept in memory: a reload continues the attempt from the server.
 */
let handed: Started | null = null

/** Call right when the output arrived: the offset is measured now. */
export function handOver(output: StartOutput) {
  storeStart(output)
  handed = { output, offset: clockOffset(output.serverTime) }
}

/** The output handed over for this attempt, once. */
export function takeHandedOver(attemptId: string): Started | null {
  const started = handed?.output.attemptId === attemptId ? handed : null
  handed = null
  return started
}
