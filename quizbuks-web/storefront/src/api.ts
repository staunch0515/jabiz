/**
 * The block's calls through the shop's app proxy (docs/quizbuks/shopify/S2-storefront.md section 3): Shopify forwards
 * {@code <proxy>/<process>} to QuizBuks, signed, with the shop and the signed-in customer; the answer is the process's
 * output, or a problem with the refusals' codes. The visitor's key goes in the query (for the per-visitor limits) and
 * in the body (for the attempt). What the block keeps in the browser is in {@link Store}.
 */

export interface Campaign {
  live: boolean
  publicationId: string | null
  name: string | null
  video: string | null
  questionCount: number
  timeLimitSec: number
  passScore: number
  percent: number
  codesToday: boolean
  /** The free plan (S3b): the block says it is QuizBuks's. */
  badge: boolean
}

export interface Option {
  no: number
  text: string
}

export interface Question {
  no: number
  stem: string
  points: number
  options: Option[]
}

export interface Answer {
  questionNo: number
  selected: number[]
}

export interface Started {
  attemptId: string
  publicationId: string
  startedAt: string
  deadline: string
  serverTime: string
  timeLimitSec: number
  currentIndex: number
  resumed: boolean
  questions: Question[]
  answers: Answer[]
}

/** How a submission ended for the code (StorefrontProcesses.SubmitOutput). */
export type Outcome = 'ISSUED' | 'AGAIN' | 'DAY_FULL' | 'NONE_LEFT' | 'NOT_PASSED' | 'CAMPAIGN_OVER'

export interface Submitted {
  attemptId: string
  score: number | null
  fullScore: number | null
  passScore: number | null
  passed: boolean
  outcome: Outcome
  discountCode: string | null
  percent: number | null
  expiresAt: string | null
}

/** A refusal of the server: its first rule code (null when it gave none) and the status. */
export class Refused extends Error {
  readonly code: string | null
  readonly status: number

  constructor(status: number, code: string | null) {
    super(code ?? `HTTP ${status}`)
    this.status = status
    this.code = code
  }
}

/** A claim the browser keeps: the code of a publication, to show again. */
export interface Claim {
  code: string
  percent: number
  expiresAt: string | null
}

const KEY = 'quizbuks:visitor'

/** Whose attempts and codes are read and kept: the signed-in customer's, or the anonymous visitor's. */
let owner = 'anon'

/**
 * What the block keeps in the visitor's browser: its random key, the attempt it started of each publication (to ask
 * for its code again) and the code it got, apart for each customer signed in on it (a shared computer shows nobody
 * another's code). Every access may fail (private windows, blocked storage): the block then works for the page's
 * life only.
 */
export const Store = {
  /** @param customer the signed-in customer's id; null or empty when nobody is signed in */
  signedIn(customer: string | null) {
    owner = customer ? `c${customer}` : 'anon'
  },
  /**
   * Takes the visitor key the block's element used (S4c: quiz-player.js has its own copy of this store; when the
   * browser's storage cannot be used, each would otherwise make its own key for the page's life).
   */
  adopt(visitor: string) {
    if (!read(KEY)) write(KEY, visitor)
  },
  visitor(): string {
    const existing = read(KEY)
    if (existing) return existing
    const made = randomKey()
    write(KEY, made)
    return made
  },
  attempt(publicationId: string): string | null {
    return read(`quizbuks:attempt:${publicationId}:${owner}`)
  },
  rememberAttempt(publicationId: string, attemptId: string) {
    write(`quizbuks:attempt:${publicationId}:${owner}`, attemptId)
  },
  claim(publicationId: string): Claim | null {
    const stored = read(`quizbuks:code:${publicationId}:${owner}`)
    if (!stored) return null
    try {
      const claim = JSON.parse(stored) as Claim
      return typeof claim.code === 'string' && typeof claim.percent === 'number' ? claim : null
    } catch {
      return null
    }
  },
  rememberClaim(publicationId: string, claim: Claim) {
    write(`quizbuks:code:${publicationId}:${owner}`, JSON.stringify(claim))
  },
}

const memory = new Map<string, string>()

/** The browser's storage; the page's memory when it cannot be used. */
function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return memory.get(key) ?? null
  }
}

function write(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    memory.set(key, value)
  }
}

/** A key nobody can guess, in the form the server takes ([A-Za-z0-9_-], 16 to 64). */
function randomKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

export class Api {
  private readonly proxy: string

  /** @param proxy the shop's app proxy path, {@code /apps/quizbuks} (behind the storefront's language prefix, if any) */
  constructor(proxy: string) {
    this.proxy = proxy.replace(/\/+$/, '')
  }

  campaign(): Promise<Campaign> {
    return this.call<Campaign>('QBS_SF_CAMPAIGN', null)
  }

  start(publicationId: string): Promise<Started> {
    return this.call<Started>('QBS_SF_START', { publicationId, visitor: Store.visitor() })
  }

  submit(publicationId: string, attemptId: string, answers: Answer[] | null): Promise<Submitted> {
    const body: Record<string, unknown> = { publicationId, attemptId, visitor: Store.visitor() }
    if (answers) body.answers = answers
    return this.call<Submitted>('QBS_SF_SUBMIT', body)
  }

  private async call<T>(process: string, body: Record<string, unknown> | null): Promise<T> {
    const url = `${this.proxy}/${process}?visitor=${encodeURIComponent(Store.visitor())}`
    const response = await fetch(url, body === null
      ? { headers: { Accept: 'application/json' }, credentials: 'same-origin' }
      : {
        method: 'POST', credentials: 'same-origin',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      })
    const answer = await response.json().catch(() => null) as Record<string, unknown> | null
    if (!response.ok) {
      const violations = answer?.violations
      const first = Array.isArray(violations) ? violations[0] as { ruleCode?: unknown } | undefined : undefined
      throw new Refused(response.status, typeof first?.ruleCode === 'string' ? first.ruleCode
        : typeof answer?.code === 'string' ? answer.code : null)
    }
    return answer as T
  }
}
