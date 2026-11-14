import { runProcess, runQuery } from '@jabiz/client'
import { readDictionary } from '../qb/api'
import { AI_CREDIT_PATH } from '../shell/paths'

/*
 * The AI credit's calls (F-M9 b1), typed by hand after `content.ai.AiCreditProcesses` and the templates
 * `qb.sponsor.ai-credit*`. The page shows the numbers the server gives and computes no money of its own: what may be
 * bought, given back or is available all come from `QB_AI_CREDIT_STANDING`.
 */

export { AI_CREDIT_PATH }

/** Everything the page reads about the credit (and the home's "to do" item) shares this key. */
export const AI_CREDIT_QUERY = ['qb', 'ai-credit'] as const
export const AI_CREDIT_STANDING_QUERY = [...AI_CREDIT_QUERY, 'standing'] as const

export const AI_CREDIT_PROCESS = {
  standing: 'QB_AI_CREDIT_STANDING',
  buy: 'QB_AI_CREDIT_BUY',
  giveBack: 'QB_AI_CREDIT_RETURN',
  questionsQuote: 'QB_AI_QUESTIONS_QUOTE',
  translateQuote: 'QB_AI_TRANSLATE_QUOTE',
  publicationTranslateQuote: 'QB_AI_PUBLICATION_TRANSLATE_QUOTE',
  siteProfileQuote: 'QB_SITE_PROFILE_QUOTE',
} as const

export const AI_CREDIT_TEMPLATE = {
  lots: 'qb.sponsor.ai-credit-lots',
  jobs: 'qb.sponsor.ai-credit-jobs',
  movements: 'qb.sponsor.ai-credit-movements',
} as const

export const AI_CREDIT_DICTIONARY = {
  jobKind: 'urn:jabiz:dict:quizbuks:ai-job-kind',
  jobStatus: 'urn:jabiz:dict:quizbuks:ai-job-status',
} as const

/** Refusal codes the page reacts to (the server words them itself). */
export const AI_CREDIT_CODE = {
  short: 'QB_AI_CREDIT_SHORT',
} as const

type Row = Record<string, unknown>

const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const numOrNull = (value: unknown): number | null => (value == null || value === '' ? null : num(value))
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))

/** `AiCreditProcesses.Standing`: null where the server cannot say (no tax rate set yet, no minimum or low amount set). */
export interface AiCreditStanding {
  feeOn: boolean
  remaining: number
  reserved: number
  unbilledWithTax: number | null
  available: number | null
  returnable: number | null
  minPurchase: number | null
  lowAmount: number | null
  low: boolean
}

export async function readAiCreditStanding(): Promise<AiCreditStanding> {
  const row = await runProcess<Row>(AI_CREDIT_PROCESS.standing, {})
  return {
    feeOn: row.feeOn === true,
    remaining: num(row.remaining),
    reserved: num(row.reserved),
    unbilledWithTax: numOrNull(row.unbilledWithTax),
    available: numOrNull(row.available),
    returnable: numOrNull(row.returnable),
    minPurchase: numOrNull(row.minPurchase),
    lowAmount: numOrNull(row.lowAmount),
    low: row.low === true,
  }
}

export interface AiCreditLot {
  lotId: string
  subAccount: 'FEE_CREDIT' | 'CASH'
  amount: number
  expiresAt: string | null
  voidedAt: string | null
}

export interface AiCreditJob {
  aiJobId: string
  kind: string
  status: string
  requestedAt: string | null
  finishedAt: string | null
  reservedYen: number | null
  feeYen: number | null
  feePeriod: string | null
  charged: boolean
}

export type MovementKind = 'BUY' | 'RETURN' | 'CHARGE' | 'EXPIRE'
export const MOVEMENT_KINDS: readonly MovementKind[] = ['BUY', 'RETURN', 'CHARGE', 'EXPIRE']

export interface AiCreditMovement {
  movementId: string
  kind: MovementKind
  amount: number
  credit: number
  cash: number
  balance: number
  movedAt: string | null
}

export async function readAiCreditLots(offset: number, limit: number) {
  const page = await runQuery<Row>(AI_CREDIT_TEMPLATE.lots, { offset, limit, count: true })
  return { total: page.total, items: page.items.map((row, i): AiCreditLot => ({
    lotId: String(row.lotId ?? i), subAccount: row.subAccount === 'FEE_CREDIT' ? 'FEE_CREDIT' : 'CASH',
    amount: num(row.amount), expiresAt: str(row.expiresAt), voidedAt: str(row.voidedAt) })) }
}

export async function readAiCreditJobs(offset: number, limit: number) {
  const page = await runQuery<Row>(AI_CREDIT_TEMPLATE.jobs, { offset, limit, count: true })
  return { total: page.total, items: page.items.map((row): AiCreditJob => ({
    aiJobId: String(row.aiJobId), kind: String(row.kind ?? ''), status: String(row.status ?? ''),
    requestedAt: str(row.requestedAt), finishedAt: str(row.finishedAt), reservedYen: numOrNull(row.reservedYen),
    feeYen: numOrNull(row.feeYen), feePeriod: str(row.feePeriod), charged: row.charged === true })) }
}

export async function readAiCreditMovements(offset: number, limit: number) {
  const page = await runQuery<Row>(AI_CREDIT_TEMPLATE.movements, { offset, limit, count: true })
  return { total: page.total, items: page.items.map((row): AiCreditMovement => ({
    movementId: String(row.movementId),
    kind: (MOVEMENT_KINDS as readonly unknown[]).includes(row.kind) ? row.kind as MovementKind : 'CHARGE',
    amount: num(row.amount), credit: num(row.credit), cash: num(row.cash), balance: num(row.balance),
    movedAt: str(row.movedAt) })) }
}

export const readAiJobKinds = () => readDictionary(AI_CREDIT_DICTIONARY.jobKind)
export const readAiJobStatuses = () => readDictionary(AI_CREDIT_DICTIONARY.jobStatus)

/** `AiCreditProcesses.BuyOutput`: how the purchase was paid (granted credit first, then cash). */
export interface AiCreditBought {
  prepaymentId: string
  amount: number
  credit: number
  cash: number
  balance: number
}

export async function buyAiCredit(amount: number, requestKey: string): Promise<AiCreditBought> {
  const row = await runProcess<Row>(AI_CREDIT_PROCESS.buy, { amount, requestKey })
  return { prepaymentId: String(row.prepaymentId ?? ''), amount: num(row.amount), credit: num(row.credit),
    cash: num(row.cash), balance: num(row.balance) }
}

export async function giveBackAiCredit(amount: number, requestKey: string): Promise<{ amount: number }> {
  const row = await runProcess<Row>(AI_CREDIT_PROCESS.giveBack, { amount, requestKey })
  return { amount: num(row.amount) }
}

/** What a request would cost at most, and whether the credit covers it (`charged` false: free, nothing to show). */
export interface AiQuote {
  charged: boolean
  maxYen: number
  availableYen: number
  enough: boolean
  shortYen: number
  minPurchase: number | null
}

export async function readAiQuote(process: string, input: Record<string, unknown>): Promise<AiQuote> {
  const row = await runProcess<Row>(process, input)
  return { charged: row.charged === true, maxYen: num(row.maxYen), availableYen: num(row.availableYen),
    enough: row.enough !== false, shortYen: num(row.shortYen), minPurchase: numOrNull(row.minPurchase) }
}
