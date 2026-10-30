/*
 * The sound effects (docs/quizbuks/plans/Q57-sound.md): short tones made by the browser's Web Audio, no sound files.
 * Played at the moments that matter (an answer's result, a reward, a published quiz, a redeemed prize), only while
 * the user's switch is on (`setSoundEnabled`, from their settings; on by default), and never before the page was
 * touched (browsers keep audio closed until then, so nothing is heard on a page opened by itself).
 */

/** What happened: each has a tone of its own. */
export type SoundCue = 'success' | 'reward' | 'wrong' | 'tap'

interface Tone {
  /** Hz. */
  frequency: number
  /** Seconds after the cue starts. */
  at: number
  /** Seconds. */
  length: number
  type?: OscillatorType
}

const TONES: Record<SoundCue, Tone[]> = {
  // A rising major third and fifth: done.
  success: [{ frequency: 523.25, at: 0, length: 0.12 }, { frequency: 659.25, at: 0.09, length: 0.12 },
    { frequency: 783.99, at: 0.18, length: 0.22 }],
  // Two bright coins.
  reward: [{ frequency: 987.77, at: 0, length: 0.08, type: 'triangle' },
    { frequency: 1318.51, at: 0.07, length: 0.3, type: 'triangle' }],
  // A soft falling step, not a buzzer.
  wrong: [{ frequency: 392, at: 0, length: 0.14 }, { frequency: 311.13, at: 0.12, length: 0.22 }],
  tap: [{ frequency: 880, at: 0, length: 0.05, type: 'triangle' }],
}

/** Quiet: an accent to the screen, not an alarm. */
const VOLUME = 0.12
const STORED = 'qb:sound'

type AudioContextClass = typeof AudioContext

let context: AudioContext | null = null
let enabled = readStored()

function readStored(): boolean {
  try {
    return globalThis.localStorage?.getItem(STORED) !== 'off'
  } catch {
    return true
  }
}

/** Whether the effects play: the user's switch (remembered on this device until the settings are read again). */
export function soundEnabled(): boolean {
  return enabled
}

export function setSoundEnabled(on: boolean) {
  enabled = on
  try {
    globalThis.localStorage?.setItem(STORED, on ? 'on' : 'off')
  } catch {
    // Storage denied (a private window): the switch still holds for this page.
  }
}

function audioClass(): AudioContextClass | null {
  if (typeof window === 'undefined') return null
  return window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioContextClass }).webkitAudioContext
    ?? null
}

/** Plays a cue when the switch is on; silently nothing where the browser has no audio or refuses it. */
export function playSound(cue: SoundCue) {
  if (!enabled) return
  // Before any touch the browser would refuse (and warn); a page opened by itself stays quiet.
  if (typeof navigator !== 'undefined' && navigator.userActivation && !navigator.userActivation.hasBeenActive) return
  const Audio = audioClass()
  if (!Audio) return
  try {
    context ??= new Audio()
    if (context.state === 'suspended') void context.resume()
    const start = context.currentTime + 0.01
    for (const tone of TONES[cue]) {
      const oscillator = context.createOscillator()
      const gain = context.createGain()
      oscillator.type = tone.type ?? 'sine'
      oscillator.frequency.value = tone.frequency
      const from = start + tone.at
      // A short attack and an exponential release: no clicks.
      gain.gain.setValueAtTime(0.0001, from)
      gain.gain.exponentialRampToValueAtTime(VOLUME, from + 0.015)
      gain.gain.exponentialRampToValueAtTime(0.0001, from + tone.length)
      oscillator.connect(gain).connect(context.destination)
      oscillator.start(from)
      oscillator.stop(from + tone.length + 0.02)
    }
  } catch {
    // Audio unavailable (no device, a blocked context): the effects are an accent only.
  }
}

/** For tests: forget the audio context (a new fake per test). */
export function resetSoundForTests() {
  context = null
  enabled = readStored()
}
