import { useSyncExternalStore } from 'react'

/*
 * Offering to install the PWA (docs/design/12-frontend.md, phase 16zj). No browser lets a site put itself on the home
 * screen: the person adds it. Where the browser has its own installation (`beforeinstallprompt`: Chrome, Edge, Samsung
 * Internet), the page keeps the event and shows the browser's confirmation when the person asks for it; Safari has no
 * such event, so iOS gets a guide to the Share menu; the browsers inside other apps (LINE, WeChat, Instagram, Facebook)
 * cannot install at all, so they get "open this in your browser".
 */

/**
 * How this browser installs the PWA:
 * - `prompt`: the browser's own confirmation (the kept `beforeinstallprompt`),
 * - `ios`: by hand from the Share menu (Safari, and other browsers on iOS 16.4 and later),
 * - `menu`: by hand from the browser's menu (Firefox on Android has no event),
 * - `in-app`: not here: the page is open inside another app's browser,
 * - `installed`: it is running from the home screen already,
 * - `none`: nothing to offer (a computer, or a browser that did not offer installation).
 */
export type InstallSituation = 'prompt' | 'ios' | 'menu' | 'in-app' | 'installed' | 'none'

/** What {@link installSituation} reads of the browser. */
export interface InstallEnvironment {
  userAgent: string
  /** `navigator.maxTouchPoints`: an iPad presents itself as a Mac, with touch. */
  maxTouchPoints: number
  /** Opened from the home screen: `display-mode: standalone` (or `fullscreen`), or Safari's `navigator.standalone`. */
  standalone: boolean
  /** Whether a `beforeinstallprompt` is kept. */
  canPrompt: boolean
}

const IN_APP = /\bLine\/|MicroMessenger|Instagram|FBAN|FBAV|FB_IAB|\bKAKAOTALK\b/i

/** Whether the device is an iPhone, iPod or iPad (which says "Macintosh" since iPadOS 13 but has a touch screen). */
export function isIos(userAgent: string, maxTouchPoints: number): boolean {
  return /iPhone|iPad|iPod/.test(userAgent) || (/Macintosh/.test(userAgent) && maxTouchPoints > 1)
}

/** How this browser installs the PWA (pure: the environment is given). */
export function installSituation(env: InstallEnvironment): InstallSituation {
  if (env.standalone) return 'installed'
  const ios = isIos(env.userAgent, env.maxTouchPoints)
  const android = /Android/.test(env.userAgent)
  if ((ios || android) && IN_APP.test(env.userAgent)) return 'in-app'
  if (!ios && !android) return 'none'
  if (env.canPrompt) return 'prompt'
  if (ios) return 'ios'
  if (/Firefox\//.test(env.userAgent)) return 'menu'
  // Chrome and the browsers like it offer the event once the page qualifies (and not again once it is installed).
  return 'none'
}

/** The event the browser fires when it would offer installation (not in the DOM types: Chromium only). */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

type Listener = () => void

let kept: BeforeInstallPromptEvent | null = null
let installed = false
let capturing = false
const listeners = new Set<Listener>()
const changed = () => listeners.forEach((listener) => listener())

/**
 * Keeps the browser's offer to install (`beforeinstallprompt`), which can come before the page has rendered, and
 * notes `appinstalled`. Called by `registerServiceWorker`; an application may call it earlier. Once only.
 */
export function captureInstallPrompt(target: Pick<Window, 'addEventListener'> | undefined =
  typeof window !== 'undefined' ? window : undefined) {
  if (capturing || !target) return
  capturing = true
  target.addEventListener('beforeinstallprompt', (event: Event) => {
    // The page offers it itself, at a moment it chooses, instead of the browser's own bar.
    event.preventDefault()
    kept = event as BeforeInstallPromptEvent
    changed()
  })
  target.addEventListener('appinstalled', () => {
    kept = null
    installed = true
    changed()
  })
}

/** Forgets what {@link captureInstallPrompt} kept (tests). */
export function resetInstallPrompt() {
  kept = null
  installed = false
  capturing = false
  changed()
}

function subscribe(listener: Listener) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

let version = 0
listeners.add(() => {
  version++
})

function browserEnvironment(): InstallEnvironment {
  if (typeof window === 'undefined') return { userAgent: '', maxTouchPoints: 0, standalone: false, canPrompt: false }
  const media = (query: string) => typeof window.matchMedia === 'function' && window.matchMedia(query).matches
  const safariStandalone = (navigator as Navigator & { standalone?: boolean }).standalone === true
  return {
    userAgent: navigator.userAgent,
    maxTouchPoints: navigator.maxTouchPoints ?? 0,
    standalone: safariStandalone || media('(display-mode: standalone)') || media('(display-mode: fullscreen)'),
    canPrompt: kept !== null,
  }
}

/** The outcome of {@link InstallPrompt.install}. */
export type InstallOutcome = 'accepted' | 'dismissed' | 'unavailable'

export interface InstallPrompt {
  situation: InstallSituation
  /** Shows the browser's confirmation (situation `prompt`); `unavailable` otherwise. The kept offer works once. */
  install: () => Promise<InstallOutcome>
}

async function install(): Promise<InstallOutcome> {
  const event = kept
  if (!event) return 'unavailable'
  kept = null
  await event.prompt()
  const { outcome } = await event.userChoice
  if (outcome === 'accepted') installed = true
  changed()
  return outcome
}

/** How this browser installs the PWA, and the installation itself where the browser offers one. */
export function useInstallPrompt(environment?: Partial<InstallEnvironment>): InstallPrompt {
  useSyncExternalStore(subscribe, () => version, () => 0)
  const env = { ...browserEnvironment(), ...environment }
  return { situation: installed ? 'installed' : installSituation(env), install }
}

/** What the page remembers of the offers the person closed. */
export interface InstallDismissals {
  /** When the last one was closed (milliseconds since the epoch). */
  at: number
  count: number
}

/** Whether to offer again: not within `days` of the last close, and never after `max` closes. */
export function installOfferAllowed(dismissals: InstallDismissals | null, now: number,
  { days = 30, max = 3 }: { days?: number; max?: number } = {}): boolean {
  if (!dismissals) return true
  return dismissals.count < max && now - dismissals.at >= days * 86_400_000
}

const storageKey = (key: string) => `jabiz.pwa.install.${key}`

/** The closes remembered under `key` in this browser (null when none, or storage is unavailable). */
export function readInstallDismissals(key: string): InstallDismissals | null {
  try {
    const value = JSON.parse(window.localStorage.getItem(storageKey(key)) ?? 'null') as Partial<InstallDismissals> | null
    return value && typeof value.at === 'number' && typeof value.count === 'number'
      ? { at: value.at, count: value.count } : null
  } catch {
    return null
  }
}

/** Remembers one more close under `key`; returns what is remembered now. */
export function rememberInstallDismissed(key: string, now: number): InstallDismissals {
  const next = { at: now, count: (readInstallDismissals(key)?.count ?? 0) + 1 }
  try {
    window.localStorage.setItem(storageKey(key), JSON.stringify(next))
  } catch {
    // A private window or blocked storage: the offer comes back next time, which is all that is lost.
  }
  return next
}
