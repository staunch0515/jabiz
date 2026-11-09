import type { PlaybackEvent } from '../video/playback'

/*
 * F-S13: how long a video was watched without a break, the two ways the rules time it (`rules.ts`): by the player's
 * own clock where the player says where it is (YouTube, Vimeo, video files: the stage's events, as Q75 reads them),
 * else by the time on the screen with the page in front. Pure, so that the runs are tested apart from the pages: a
 * pause, a seek, the end of the video or the page going to the background starts the run again from 0.
 */

export interface Run {
  /** Seconds of the run so far. */
  sec: number
  /** The anchor: the player's last time (player) and the clock then, while it runs. */
  lastTime: number | null
  lastAt: number | null
}

export const NO_RUN: Run = { sec: 0, lastTime: null, lastAt: null }

/** The pace up to which the player's time counts (Q75's: twice the clock), and the slack for coarse reports. */
const MAX_PACE = 2
const TOLERANCE_SEC = 1
/** The most one screen tick adds (a device asleep between ticks does not watch). */
const MAX_TICK_SEC = 2

/** The run after a player's event: consecutive reports while playing add up; anything else breaks the run. */
export function playerRun(run: Run, event: PlaybackEvent): Run {
  switch (event.type) {
    case 'time': {
      if (!event.playing) return NO_RUN
      if (run.lastTime == null || run.lastAt == null) {
        return { ...run, lastTime: event.currentTime, lastAt: event.at }
      }
      const delta = event.currentTime - run.lastTime
      const wall = Math.max(0, (event.at - run.lastAt) / 1000)
      if (delta < 0 || delta > wall * MAX_PACE + TOLERANCE_SEC) {
        // A seek or a rewind: a new run from here.
        return { sec: 0, lastTime: event.currentTime, lastAt: event.at }
      }
      return { sec: run.sec + delta, lastTime: event.currentTime, lastAt: event.at }
    }
    case 'state':
      // A pause breaks the run; playing again anchors afresh.
      return event.playing ? { ...run, lastTime: null, lastAt: null } : NO_RUN
    case 'ended':
      // What played to the end stays (a video shorter than the seconds counts once played through).
      return { ...run, lastTime: null, lastAt: null }
    case 'suspend':
      return NO_RUN
    default:
      return run
  }
}

/** The run after a tick of the screen's clock at `at` (ms): on the screen with the page in front, or not. */
export function screenRun(run: Run, at: number, shown: boolean): Run {
  if (!shown) return NO_RUN
  if (run.lastAt == null) return { ...run, lastAt: at }
  const add = Math.min(MAX_TICK_SEC, Math.max(0, (at - run.lastAt) / 1000))
  return { ...run, sec: run.sec + add, lastAt: at }
}
