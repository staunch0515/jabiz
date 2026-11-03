import { runProcess, runQuery } from '@jabiz/client'
import { minuteNow } from '../qb/legal'
import { BENEFIT_PROCESS, BENEFIT_TEMPLATE, type BenefitCertification, type BenefitKind } from './ids'

/*
 * Sponsored benefits (Q89a): typed by hand after `OfferProcesses`, `BenefitConversationProcesses`,
 * `PurchaseProcesses` and the `qb.sponsor.benefit-*` templates. A sponsor is either side: the provider of its own
 * benefits, the sponsor (buyer) of others'. The other side shows by its company, never its user id.
 */

export const BENEFITS_QUERY = ['qb', 'benefits'] as const

export const BENEFITS_PATH = '/benefits'
export const OFFERS_PATH = '/benefits/offers'
export const BENEFIT_CONVERSATIONS_PATH = '/benefits/conversations'
export const PURCHASES_PATH = '/benefits/purchases'
export const NEW_PURCHASE = 'new'

/** Which side of a conversation or a purchase a page shows: the sponsor's (buying) or the provider's. */
export type Side = 'SPONSOR' | 'PROVIDER'
export const sideOf = (search: string): Side =>
  new URLSearchParams(search).get('as') === 'provider' ? 'PROVIDER' : 'SPONSOR'
const asParam = (side: Side) => (side === 'PROVIDER' ? '?as=provider' : '')

export const conversationPath = (id: string, side: Side) =>
  `${BENEFIT_CONVERSATIONS_PATH}/${encodeURIComponent(id)}${asParam(side)}`
export const purchasePath = (id: string, side: Side) => `${PURCHASES_PATH}/${encodeURIComponent(id)}${asParam(side)}`
export const newPurchasePath = (offerId: string) =>
  `${PURCHASES_PATH}/${NEW_PURCHASE}?offer=${encodeURIComponent(offerId)}`

/** The id in a path such as `/benefits/conversations/<id>` (`prefix` without the slash), or null. */
export function idOf(path: string, prefix: string): string | null {
  if (!path.startsWith(`${prefix}/`)) return null
  const rest = path.slice(prefix.length + 1)
  if (!rest || rest.includes('/')) return null
  try {
    return decodeURIComponent(rest)
  } catch {
    return null
  }
}

export interface Access {
  certification: BenefitCertification
  heldUntil: string | null
  roundPublicationId: string | null
}

export interface Offer {
  offerId: string
  kind: BenefitKind
  name: string
  description: string
  images: string[]
  unitValue: number
  pickupAddress: string | null
  pickupHours: string | null
  terms: string | null
  status?: 'LISTED' | 'UNLISTED' | 'TAKEN_DOWN'
  revision?: number
  providerCompany?: string | null
  conversationId?: string | null
}

export interface OfferInput {
  offerId?: string
  kind: BenefitKind
  name: string
  description: string
  images: string[]
  unitValue: string
  pickupAddress: string
  pickupHours: string
  terms: string
  listed: boolean
  baseRevision?: number
}

export interface Conversation {
  conversationId: string
  offerId: string
  offerName: string
  otherCompany: string | null
  lastMessageAt: string
  lastSender: Side
  lastBody: string | null
  unread: boolean
}

export interface Message {
  messageId: string
  sender: Side
  body: string | null
  createdAt: string
  mine: boolean
}

export type PurchaseStatus = 'DRAFT' | 'PENDING_PROVIDER' | 'CONFIRMED' | 'SUSPENDED' | 'CLOSED' | 'CANCELLED'

export interface Purchase {
  purchaseId: string
  offerId: string
  otherCompany: string | null
  kind: BenefitKind
  title: string
  unitValue: number
  pickupAddress: string | null
  pickupHours: string | null
  terms: string | null
  quantity: number
  paidAmount: number
  paidOn: string
  proofs: string[]
  note: string | null
  status: PurchaseStatus
  submittedAt: string | null
  confirmedAt: string | null
  confirmedRevision: number | null
  declineReason: string | null
  editedAt: string
  revision: number
}

export interface PurchaseInput {
  purchaseId?: string
  offerId?: string
  quantity: string
  paidAmount: string
  paidOn: string
  proofs: string[]
  note: string
  baseRevision?: number
}

type Row = Record<string, unknown>
const text = (value: unknown) => (value === null || value === undefined || value === '' ? null : String(value))
const number = (value: unknown) => Number(value ?? 0)
const files = (row: Row, prefix: string, count: number) =>
  Array.from({ length: count }, (_, i) => text(row[`${prefix}${i + 1}`])).filter((id): id is string => id !== null)

function offerOf(row: Row): Offer {
  return {
    offerId: String(row.offerId),
    kind: String(row.kind) as BenefitKind,
    name: String(row.name ?? ''),
    description: String(row.description ?? ''),
    images: files(row, 'image', 4),
    unitValue: number(row.unitValue),
    pickupAddress: text(row.pickupAddress),
    pickupHours: text(row.pickupHours),
    terms: text(row.terms),
    status: row.status ? (String(row.status) as Offer['status']) : undefined,
    revision: row.revision === undefined ? undefined : number(row.revision),
    providerCompany: text(row.providerCompany),
    conversationId: text(row.conversationId),
  }
}

export async function readAccess(): Promise<Access[]> {
  const page = await runQuery<Row>(BENEFIT_TEMPLATE.access, { params: { at: minuteNow() }, limit: 10 })
  return page.items.map((row) => ({
    certification: String(row.certification) as BenefitCertification,
    heldUntil: text(row.heldUntil),
    roundPublicationId: text(row.roundPublicationId),
  }))
}

export async function readCatalog(filter: { kind?: BenefitKind | null; q?: string; offerId?: string } = {})
  : Promise<Offer[]> {
  const params: Row = { at: minuteNow() }
  if (filter.kind) params.kind = filter.kind
  if (filter.q?.trim()) params.q = filter.q.trim()
  if (filter.offerId) params.offerId = filter.offerId
  return (await runQuery<Row>(BENEFIT_TEMPLATE.catalog, { params, limit: 200 })).items.map(offerOf)
}

export async function readMyOffers(): Promise<Offer[]> {
  return (await runQuery<Row>(BENEFIT_TEMPLATE.offers, { params: {}, limit: 100 })).items.map(offerOf)
}

export const saveOffer = (input: OfferInput) =>
  runProcess<{ offerId: string; status: string; revision: number }>(BENEFIT_PROCESS.offerSave, {
    ...input,
    pickupAddress: input.pickupAddress.trim() || null,
    pickupHours: input.pickupHours.trim() || null,
    terms: input.terms.trim() || null,
  })

export async function readConversations(side: Side, conversationId?: string): Promise<Conversation[]> {
  const template = side === 'PROVIDER' ? BENEFIT_TEMPLATE.inquiries : BENEFIT_TEMPLATE.conversations
  const page = await runQuery<Row>(template, { params: conversationId ? { conversationId } : {}, limit: 200 })
  return page.items.map((row) => ({
    conversationId: String(row.conversationId),
    offerId: String(row.offerId),
    offerName: String(row.offerName ?? ''),
    otherCompany: text(row.otherCompany),
    lastMessageAt: String(row.lastMessageAt ?? ''),
    lastSender: String(row.lastSender) as Side,
    lastBody: text(row.lastBody),
    unread: row.unread === true,
  }))
}

export async function readMessages(side: Side, conversationId: string): Promise<Message[]> {
  const template = side === 'PROVIDER' ? BENEFIT_TEMPLATE.inquiryMessages : BENEFIT_TEMPLATE.messages
  const page = await runQuery<Row>(template, { params: { conversationId }, limit: 500 })
  return page.items.map((row) => ({
    messageId: String(row.messageId),
    sender: String(row.sender) as Side,
    body: text(row.body),
    createdAt: String(row.createdAt ?? ''),
    mine: row.mine === true,
  }))
}

export const openConversation = (offerId: string, body: string) =>
  runProcess<{ conversationId: string; created: boolean }>(BENEFIT_PROCESS.open, { offerId, body })
export const sendMessage = (conversationId: string, body: string) =>
  runProcess<{ conversationId: string }>(BENEFIT_PROCESS.send, { conversationId, body })
export const markRead = (conversationId: string) =>
  runProcess<{ conversationId: string }>(BENEFIT_PROCESS.read, { conversationId })

export async function readPurchases(side: Side, purchaseId?: string): Promise<Purchase[]> {
  const template = side === 'PROVIDER' ? BENEFIT_TEMPLATE.orders : BENEFIT_TEMPLATE.purchases
  const page = await runQuery<Row>(template, { params: purchaseId ? { purchaseId } : {}, limit: 200 })
  return page.items.map((row) => ({
    purchaseId: String(row.purchaseId),
    offerId: String(row.offerId),
    otherCompany: text(row.otherCompany),
    kind: String(row.kind) as BenefitKind,
    title: String(row.title ?? ''),
    unitValue: number(row.unitValue),
    pickupAddress: text(row.pickupAddress),
    pickupHours: text(row.pickupHours),
    terms: text(row.terms),
    quantity: number(row.quantity),
    paidAmount: number(row.paidAmount),
    paidOn: String(row.paidOn ?? ''),
    proofs: files(row, 'proof', 5),
    note: text(row.note),
    status: String(row.status) as PurchaseStatus,
    submittedAt: text(row.submittedAt),
    confirmedAt: text(row.confirmedAt),
    confirmedRevision: row.confirmedRevision === null || row.confirmedRevision === undefined ? null
      : number(row.confirmedRevision),
    declineReason: text(row.declineReason),
    editedAt: String(row.editedAt ?? ''),
    revision: number(row.revision),
  }))
}

type PurchaseOutput = { purchaseId: string; status: PurchaseStatus; revision: number }

export const savePurchase = (input: PurchaseInput) =>
  runProcess<PurchaseOutput>(BENEFIT_PROCESS.purchaseSave, { ...input, note: input.note.trim() || null })
export const submitPurchase = (purchaseId: string, baseRevision: number) =>
  runProcess<PurchaseOutput>(BENEFIT_PROCESS.submit, { purchaseId, baseRevision })
export const confirmPurchase = (purchaseId: string, baseRevision: number) =>
  runProcess<PurchaseOutput>(BENEFIT_PROCESS.confirm, { purchaseId, baseRevision })
export const declinePurchase = (purchaseId: string, reason: string, baseRevision: number) =>
  runProcess<PurchaseOutput>(BENEFIT_PROCESS.decline, { purchaseId, reason, baseRevision })
export const cancelPurchase = (purchaseId: string, baseRevision: number) =>
  runProcess<PurchaseOutput>(BENEFIT_PROCESS.cancel, { purchaseId, baseRevision })

/** Q89b-2: a row of `qb.sponsor.benefit-publications`: a buyer's publication giving away what the caller provides. */
export interface ProvidedPublication {
  publicationId: string
  name: string
  status: string
  rewardMethod: string
  digitalDelivery: string | null
  maxWinners: number | null
  sponsorCompany: string | null
  available: number
  issued: number
}

export async function readProvidedPublications(purchaseId: string): Promise<ProvidedPublication[]> {
  const page = await runQuery<Record<string, unknown>>(BENEFIT_TEMPLATE.publications,
    { params: { purchaseId }, limit: 200 })
  return page.items.map((row) => ({
    publicationId: String(row.publicationId),
    name: String(row.name ?? ''),
    status: String(row.status ?? ''),
    rewardMethod: String(row.rewardMethod ?? ''),
    digitalDelivery: row.digitalDelivery == null ? null : String(row.digitalDelivery),
    maxWinners: row.maxWinners == null ? null : Number(row.maxWinners),
    sponsorCompany: row.sponsorCompany == null ? null : String(row.sponsorCompany),
    available: Number(row.available ?? 0),
    issued: Number(row.issued ?? 0),
  }))
}
