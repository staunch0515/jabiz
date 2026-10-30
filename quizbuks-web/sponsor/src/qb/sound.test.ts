import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { playSound, resetSoundForTests, setSoundEnabled, soundEnabled } from './sound'

/** A Web Audio stand-in that counts the tones started. */
function fakeAudio() {
  const started: number[] = []
  class FakeContext {
    state = 'running'
    currentTime = 0
    destination = {}
    resume = vi.fn(async () => undefined)
    createOscillator() {
      const node = { type: 'sine', frequency: { value: 0 }, connect: (next: unknown) => next,
        start: () => started.push(node.frequency.value), stop: () => undefined }
      return node
    }
    createGain() {
      const ramp = () => undefined
      return { gain: { setValueAtTime: ramp, exponentialRampToValueAtTime: ramp }, connect: (next: unknown) => next }
    }
  }
  vi.stubGlobal('AudioContext', FakeContext)
  return started
}

describe('the sound effects (Q57)', () => {
  beforeEach(() => {
    localStorage.clear()
    resetSoundForTests()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    resetSoundForTests()
  })

  it('play a cue\'s tones while the switch is on, which it is by default', () => {
    const started = fakeAudio()
    expect(soundEnabled()).toBe(true)
    playSound('success')
    expect(started).toEqual([523.25, 659.25, 783.99])
  })

  it('stay silent once switched off, and the device remembers it until the settings are read', () => {
    const started = fakeAudio()
    setSoundEnabled(false)
    playSound('reward')
    expect(started).toEqual([])
    resetSoundForTests()
    expect(soundEnabled()).toBe(false)
    setSoundEnabled(true)
    playSound('tap')
    expect(started).toEqual([880])
  })

  it('stay silent before the page was touched, and where the browser has no audio', () => {
    const started = fakeAudio()
    vi.stubGlobal('navigator', { userActivation: { hasBeenActive: false } })
    playSound('success')
    expect(started).toEqual([])
    vi.unstubAllGlobals()
    vi.stubGlobal('AudioContext', undefined)
    expect(() => playSound('success')).not.toThrow()
  })
})
