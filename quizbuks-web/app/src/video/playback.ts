import { useSyncExternalStore } from 'react'

/*
 * What the app knows of the watching of a publication's video (Q75, docs/quizbuks/plans/Q75-video-playback.md): the
 * player tells the stage (`stage.tsx`) where it is every so often; this reduces those reports to seconds watched and
 * whether the video ended, by the rule of D-Q75-2, and holds the start page's decision of D-Q75-4 — the same rule the
 * server applies to the report (`play.WatchRules`; `WatchRulesTest` has these cases too).
 *
 * The record is per publication, kept in this tab's sessionStorage (the home's card and the start page share it;
 * closing the tab forgets it, as the attempts kept there). Storage can be missing or refuse: every access is guarded.
 */

export type PlaybackStatus = 'REPORTED' | 'UNKNOWN'

export interface PlaybackRecord {
  /** Seconds watched at normal pace (fractions kept; the report rounds down). */
  watchedSec: number
  /** Whether the player reported the end of the video. */
  ended: boolean
  /** The duration the player told; kept beside the sponsor's for comparison, never used instead of it. */
  durationSec: number | null
  /** Whether the player was heard from (REPORTED), or gave nothing within the wait (UNKNOWN, D-Q75-6). */
  status: PlaybackStatus | null
  /** The anchor of the counting: the player's last time and the clock then, while playing. */
  lastTime: number | null
  lastAt: number | null
  playing: boolean
}

export const EMPTY: PlaybackRecord = { watchedSec: 0, ended: false, durationSec: null, status: null, lastTime: null,
  lastAt: null, playing: false }

/** The pace up to which time counts (D-Q75-2: twice the clock allows 2× playback; skipping ahead is not watching). */
export const MAX_PACE = 2
/** The slack over the pace, in seconds, for the players' coarse reports. */
const TOLERANCE_SEC = 1
/** The share of the duration that counts as watched (D-Q75-4, the server's `WatchRules.WATCHED_SHARE`). */
export const WATCHED_SHARE = 0.9
/** How long the stage waits for a first report from a playing player before giving up on it (D-Q75-6). */
export const UNKNOWN_AFTER_MS = 8_000

export type PlaybackEvent =
  /** The player's current time while it plays or pauses (YouTube's `infoDelivery`, Vimeo's `timeupdate`). */
  | { type: 'time'; currentTime: number; playing: boolean; at: number }
  /** The player started or stopped playing (YouTube's state, Vimeo's `play` / `pause`). */
  | { type: 'state'; playing: boolean; at: number }
  /** The video reached its end. */
  | { type: 'ended' }
  /** The player said how long the video is. */
  | { type: 'duration'; durationSec: number }
  /** The player could not be heard (no report within the wait): let through and marked, D-Q75-6. */
  | { type: 'unknown' }
  /** Counting stops without the player stopping (the home's muted card, D-Q75-3): the anchor is dropped. */
  | { type: 'suspend' }

/** The record after an event (D-Q75-2): only consecutive reports while playing count, at most 2× the clock. */
export function reduce(record: PlaybackRecord, event: PlaybackEvent): PlaybackRecord {
  switch (event.type) {
    case 'time': {
      let watched = record.watchedSec
      if (record.playing && record.lastTime != null && record.lastAt != null) {
        const delta = event.currentTime - record.lastTime
        const wall = Math.max(0, (event.at - record.lastAt) / 1000)
        // A jump ahead beyond the pace is a seek, backwards a rewind: neither counts; the anchor moves on.
        if (delta > 0 && delta <= wall * MAX_PACE + TOLERANCE_SEC) watched += delta
      }
      return { ...record, status: 'REPORTED', watchedSec: watched, playing: event.playing,
        lastTime: event.playing ? event.currentTime : null, lastAt: event.playing ? event.at : null }
    }
    case 'state':
      // A pause or a fresh play drops the anchor: a seek while paused must not count on resuming.
      return { ...record, status: 'REPORTED', playing: event.playing, lastTime: null, lastAt: null }
    case 'ended':
      return { ...record, status: 'REPORTED', ended: true, playing: false, lastTime: null, lastAt: null }
    case 'duration':
      return { ...record, status: 'REPORTED', durationSec: event.durationSec > 0 ? event.durationSec : record.durationSec }
    case 'unknown':
      return record.status == null ? { ...record, status: 'UNKNOWN' } : record
    case 'suspend':
      return { ...record, playing: false, lastTime: null, lastAt: null }
  }
}

/** The seconds that count as watched: nine tenths of the duration, rounded up; null without a duration. */
export function needed(durationSec: number | null): number | null {
  return durationSec == null ? null : Math.ceil(durationSec * WATCHED_SHARE - 1e-9)
}

/**
 * Whether the start may go on (D-Q75-4, D-Q75-6): the video ended, or enough of it was watched by the sponsor's
 * duration, or the player could not be read. Without a duration on the card only the end counts.
 */
export function satisfied(record: PlaybackRecord, cardDurationSec: number | null): boolean {
  if (record.status === 'UNKNOWN') return true
  if (record.ended) return true
  const target = needed(cardDurationSec)
  return target != null && Math.floor(record.watchedSec) >= target
}

/** How many seconds are still to watch; 0 when only the end counts or when satisfied. */
export function shortfallSec(record: PlaybackRecord, cardDurationSec: number | null): number {
  if (satisfied(record, cardDurationSec)) return 0
  const target = needed(cardDurationSec)
  return target == null ? 0 : Math.max(0, target - Math.floor(record.watchedSec))
}

/** `WatchRules.Playback`: the report sent with `QB_ATTEMPT_START`; none before the player was heard or given up on. */
export interface PlaybackInput {
  status: PlaybackStatus
  watchedSec?: number
  ended?: boolean
  reportedDurationSec?: number
}

export function toInput(record: PlaybackRecord): PlaybackInput | null {
  if (record.status == null) return null
  if (record.status === 'UNKNOWN') return { status: 'UNKNOWN' }
  return { status: 'REPORTED', watchedSec: Math.min(99_999, Math.floor(record.watchedSec)), ended: record.ended,
    ...(record.durationSec != null ? { reportedDurationSec: Math.min(99_999, Math.round(record.durationSec)) } : {}) }
}

/** `m:ss` of a number of seconds, for the start page's progress. */
export function clock(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}

// ---- the records of this tab, by publication -----------------------------------------------------------------------

const KEY = 'qb.play.playback.'
const records = new Map<string, PlaybackRecord>()
const listeners = new Set<() => void>()

function stored(publicationId: string): PlaybackRecord {
  try {
    const text = window.sessionStorage.getItem(KEY + publicationId)
    if (text) {
      const kept = JSON.parse(text) as Partial<PlaybackRecord>
      // What crosses pages: the seconds, the end, the duration and the status; the anchor starts afresh.
      return { ...EMPTY, watchedSec: Number(kept.watchedSec) || 0, ended: kept.ended === true,
        durationSec: typeof kept.durationSec === 'number' ? kept.durationSec : null,
        status: kept.status === 'REPORTED' || kept.status === 'UNKNOWN' ? kept.status : null }
    }
  } catch {
    // Without storage the record lives in memory only.
  }
  return EMPTY
}

export function playbackOf(publicationId: string): PlaybackRecord {
  let record = records.get(publicationId)
  if (!record) {
    record = stored(publicationId)
    records.set(publicationId, record)
  }
  return record
}

/** Applies an event to a publication's record, keeps it and tells the pages. */
export function recordPlayback(publicationId: string, event: PlaybackEvent) {
  const next = reduce(playbackOf(publicationId), event)
  records.set(publicationId, next)
  try {
    window.sessionStorage.setItem(KEY + publicationId, JSON.stringify({ watchedSec: next.watchedSec, ended: next.ended,
      durationSec: next.durationSec, status: next.status }))
  } catch {
    // Kept in memory only.
  }
  listeners.forEach((listener) => listener())
}

/** Forgets every record (on signing out, with the attempts). */
export function forgetPlayback() {
  records.clear()
  try {
    const keys: string[] = []
    for (let i = 0; i < window.sessionStorage.length; i++) {
      const key = window.sessionStorage.key(i)
      if (key?.startsWith(KEY)) keys.push(key)
    }
    keys.forEach((key) => window.sessionStorage.removeItem(key))
  } catch {
    // Nothing kept.
  }
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** A publication's record as it changes. */
export function usePlayback(publicationId: string): PlaybackRecord {
  return useSyncExternalStore(subscribe, () => playbackOf(publicationId), () => playbackOf(publicationId))
}
