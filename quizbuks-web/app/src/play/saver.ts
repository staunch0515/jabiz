import type { Given, SaveInput, SaveOutput } from '../qb/play'

/** How long answering stays quiet before the answers so far are saved. */
export const SAVE_DELAY_MS = 800

/** How long after a failed save it is tried again (unless another answer saves first). */
export const RETRY_DELAY_MS = 5_000

export interface SaverOptions {
  attemptId: string
  save: (input: SaveInput) => Promise<SaveOutput>
  delayMs?: number
  retryMs?: number
  /** A save failed; the answers stay pending and are tried again. */
  onError?: (error: unknown) => void
  /** A failure that no retry can mend (the attempt is over): saving stops and this runs instead of `onError`. */
  isFatal?: (error: unknown) => boolean
  onFatal?: (error: unknown) => void
  /** Everything pending was saved. */
  onSaved?: (output: SaveOutput) => void
}

/**
 * Saves an attempt's progress (U-41; `QB_ATTEMPT_SAVE`): answers are saved a moment after the taker stops changing
 * them, a move to another question at once. Saves run one after another (the attempt's version guards it: two at
 * once would be 409), each sending only the questions changed since the last one that succeeded. Nothing is lost
 * when a save fails: its answers stay pending unless changed again meanwhile, and are sent with the next save.
 */
export class AnswerSaver {
  private readonly options: SaverOptions & { delayMs: number; retryMs: number }
  private pending = new Map<number, number[]>()
  private index: number | null = null
  private timer: ReturnType<typeof setTimeout> | undefined
  private chain: Promise<void> = Promise.resolve()
  private stopped = false

  constructor(options: SaverOptions) {
    this.options = { delayMs: SAVE_DELAY_MS, retryMs: RETRY_DELAY_MS, ...options }
  }

  /** The taker changed a question's answer. */
  answer(questionNo: number, selected: number[]) {
    if (this.stopped) return
    this.pending.set(questionNo, [...selected].sort((a, b) => a - b))
    this.schedule(this.options.delayMs)
  }

  /** The taker moved to another question (from 0): saved at once, with any pending answers. */
  move(index: number): Promise<void> {
    if (this.stopped) return this.chain
    this.index = index
    return this.flush()
  }

  /** Whether some change has not been saved yet. */
  get dirty(): boolean {
    return this.pending.size > 0 || this.index !== null
  }

  /** Saves what is pending now; resolves once that save (and those before it) ended. */
  flush(): Promise<void> {
    this.clearTimer()
    if (this.stopped || !this.dirty) return this.chain
    const answers: Given[] = [...this.pending].map(([questionNo, selected]) => ({ questionNo, selected }))
    const index = this.index
    this.pending = new Map()
    this.index = null
    this.chain = this.chain.then(async () => {
      if (this.stopped) return
      try {
        const output = await this.options.save({
          attemptId: this.options.attemptId,
          currentIndex: index,
          answers,
        })
        if (!this.dirty) this.options.onSaved?.(output)
      } catch (error) {
        if (this.stopped) return
        if (this.options.isFatal?.(error)) {
          this.halt()
          this.options.onFatal?.(error)
          return
        }
        // Back to pending what was not changed again meanwhile; tried again shortly.
        for (const answer of answers) {
          if (!this.pending.has(answer.questionNo)) this.pending.set(answer.questionNo, answer.selected)
        }
        if (this.index === null) this.index = index
        this.options.onError?.(error)
        this.schedule(this.options.retryMs)
      }
    })
    return this.chain
  }

  /**
   * Stops saving (the attempt is being submitted with all its answers, or is over) and resolves once a save under
   * way ended, so that the submission does not race it.
   */
  stop(): Promise<void> {
    this.halt()
    return this.chain
  }

  /** Saves again after {@link stop} (a submission that failed and can be tried again). */
  resume() {
    this.stopped = false
  }

  private halt() {
    this.stopped = true
    this.clearTimer()
    this.pending = new Map()
    this.index = null
  }

  private schedule(delay: number) {
    this.clearTimer()
    this.timer = setTimeout(() => void this.flush(), delay)
  }

  private clearTimer() {
    if (this.timer !== undefined) clearTimeout(this.timer)
    this.timer = undefined
  }
}
