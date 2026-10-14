import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SaveInput, SaveOutput } from '../qb/play'
import { AnswerSaver } from './saver'

function output(input: SaveInput): SaveOutput {
  return { attemptId: input.attemptId, currentIndex: input.currentIndex ?? 0, answers: input.answers ?? [],
    deadline: '2026-10-14T12:05:00Z', serverTime: '2026-10-14T12:00:00Z' }
}

/** Lets the saver's promise chain run. */
async function settle() {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}

describe('saving an attempt\'s progress', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('saves answers a moment after the taker stops changing them, once, with the latest choice', async () => {
    const save = vi.fn(async (input: SaveInput) => output(input))
    const saver = new AnswerSaver({ attemptId: 'a1', save, delayMs: 800 })
    saver.answer(1, [2])
    vi.advanceTimersByTime(500)
    saver.answer(1, [3, 2])
    saver.answer(2, [1])
    vi.advanceTimersByTime(799)
    expect(save).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    await settle()
    expect(save).toHaveBeenCalledTimes(1)
    expect(save).toHaveBeenCalledWith({ attemptId: 'a1', currentIndex: null,
      answers: [{ questionNo: 1, selected: [2, 3] }, { questionNo: 2, selected: [1] }] })
  })

  it('saves a move to another question at once, with what is pending', async () => {
    const save = vi.fn(async (input: SaveInput) => output(input))
    const saver = new AnswerSaver({ attemptId: 'a1', save })
    saver.answer(1, [1])
    await saver.move(1)
    expect(save).toHaveBeenCalledWith({ attemptId: 'a1', currentIndex: 1, answers: [{ questionNo: 1, selected: [1] }] })
    // Nothing left to save: the timer of the answer was cancelled.
    vi.advanceTimersByTime(5_000)
    await settle()
    expect(save).toHaveBeenCalledTimes(1)
  })

  it('runs one save at a time, the next after the one under way', async () => {
    let release!: () => void
    const order: string[] = []
    const save = vi.fn((input: SaveInput) => {
      order.push(`start ${input.currentIndex}`)
      return new Promise<SaveOutput>((resolve) => {
        release = () => {
          order.push(`end ${input.currentIndex}`)
          resolve(output(input))
        }
      })
    })
    const saver = new AnswerSaver({ attemptId: 'a1', save })
    const first = saver.move(1)
    await settle()
    const second = saver.move(2)
    await settle()
    expect(order).toEqual(['start 1'])
    release()
    await first
    await settle()
    expect(order).toEqual(['start 1', 'end 1', 'start 2'])
    release()
    await second
  })

  it('keeps what failed to save and sends it again, unless changed meanwhile', async () => {
    const onError = vi.fn()
    const onSaved = vi.fn()
    const save = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockImplementation(async (input: SaveInput) => output(input))
    const saver = new AnswerSaver({ attemptId: 'a1', save, onError, onSaved, retryMs: 5_000 })
    saver.answer(1, [1])
    saver.answer(2, [2])
    await saver.flush()
    expect(onError).toHaveBeenCalledTimes(1)
    expect(saver.dirty).toBe(true)
    // Question 2 changes before the retry: its newer answer is sent, question 1's failed one with it.
    saver.answer(2, [1])
    vi.advanceTimersByTime(800)
    await settle()
    expect(save).toHaveBeenLastCalledWith({ attemptId: 'a1', currentIndex: null,
      answers: [{ questionNo: 1, selected: [1] }, { questionNo: 2, selected: [1] }] })
    expect(onSaved).toHaveBeenCalledTimes(1)
    expect(saver.dirty).toBe(false)
  })

  it('stops for good on a failure no retry can mend', async () => {
    const onFatal = vi.fn()
    const onError = vi.fn()
    const save = vi.fn().mockRejectedValue(new Error('expired'))
    const saver = new AnswerSaver({ attemptId: 'a1', save, onError, onFatal, isFatal: () => true })
    saver.answer(1, [1])
    await saver.flush()
    expect(onFatal).toHaveBeenCalledTimes(1)
    expect(onError).not.toHaveBeenCalled()
    saver.answer(1, [2])
    vi.advanceTimersByTime(10_000)
    await settle()
    expect(save).toHaveBeenCalledTimes(1)
  })

  it('stops before a submission, waiting for the save under way', async () => {
    let release!: () => void
    const save = vi.fn(() => new Promise<SaveOutput>((resolve) => {
      release = () => resolve(output({ attemptId: 'a1' }))
    }))
    const saver = new AnswerSaver({ attemptId: 'a1', save })
    void saver.move(1)
    await settle()
    saver.answer(1, [1])
    let stopped = false
    const stopping = saver.stop().then(() => {
      stopped = true
    })
    await settle()
    expect(stopped).toBe(false)
    release()
    await stopping
    // The pending answer was dropped: the submission carries every answer itself.
    vi.advanceTimersByTime(10_000)
    await settle()
    expect(save).toHaveBeenCalledTimes(1)
  })
})
