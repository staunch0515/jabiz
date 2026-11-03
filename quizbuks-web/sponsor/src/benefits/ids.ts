/**
 * The names the console's sponsored benefits call on the server (Q89a, docs/quizbuks/plans/Q89-sponsored-benefits.md).
 * Only the console lists, asks about and buys benefits, so they live here rather than in the shared `src/qb/ids.ts`.
 */

export const BENEFIT_PROCESS = {
  offerSave: 'QB_BENEFIT_OFFER_SAVE',
  open: 'QB_BENEFIT_CONVERSATION_OPEN',
  send: 'QB_BENEFIT_CONVERSATION_SEND',
  read: 'QB_BENEFIT_CONVERSATION_READ',
  purchaseSave: 'QB_BENEFIT_PURCHASE_SAVE',
  submit: 'QB_BENEFIT_PURCHASE_SUBMIT',
  confirm: 'QB_BENEFIT_PURCHASE_CONFIRM',
  decline: 'QB_BENEFIT_PURCHASE_DECLINE',
  cancel: 'QB_BENEFIT_PURCHASE_CANCEL',
  /** Q89d-1: suspending and resuming a purchase; its incidents. */
  suspend: 'QB_BENEFIT_PURCHASE_SUSPEND',
  resume: 'QB_BENEFIT_PURCHASE_RESUME',
  incidentRecord: 'QB_BENEFIT_INCIDENT_RECORD',
  incidentResolve: 'QB_BENEFIT_INCIDENT_RESOLVE',
} as const

export const BENEFIT_TEMPLATE = {
  access: 'qb.sponsor.benefit-access',
  catalog: 'qb.sponsor.benefit-catalog',
  offers: 'qb.sponsor.benefit-offers',
  conversations: 'qb.sponsor.benefit-conversations',
  inquiries: 'qb.sponsor.benefit-inquiries',
  messages: 'qb.sponsor.benefit-messages',
  inquiryMessages: 'qb.sponsor.benefit-inquiry-messages',
  purchases: 'qb.sponsor.benefit-purchases',
  orders: 'qb.sponsor.benefit-orders',
  incidents: 'qb.sponsor.benefit-incidents',
  orderIncidents: 'qb.sponsor.benefit-order-incidents',
  publications: 'qb.sponsor.benefit-publications',
  digitalPoolSummary: 'qb.sponsor.benefit-digital-pool-summary',
  digitalCodes: 'qb.sponsor.benefit-digital-codes',
} as const

/** `benefit.QbBenefits` and `BenefitRules`: the policies, kinds and limits the server applies. */
export const BENEFIT = {
  imagePolicy: 'qb.benefit-image',
  proofPolicy: 'qb.benefit-proof',
  kinds: ['GOODS', 'SERVICE', 'DIGITAL'],
  certifications: ['BENEFIT_PROVIDER', 'BENEFIT_SPONSOR'],
  maxImages: 4,
  maxProofs: 5,
  nameLength: 100,
  descriptionLength: 2000,
  addressLength: 500,
  hoursLength: 200,
  termsLength: 1000,
  noteLength: 1000,
  reasonLength: 500,
  messageLength: 2000,
  /** 422: the caller does not hold the certification the act needs. */
  notCertified: 'QB_BENEFIT_NOT_CERTIFIED',
  /** 422: a purchase changed since the page read it. */
  purchaseChanged: 'QB_BENEFIT_PURCHASE_CHANGED',
} as const

/** Q89d-1: `QbBenefits.INCIDENT_KINDS`. */
export const INCIDENT_KINDS = ['NOT_FULFILLED', 'CLOSED_BUSINESS', 'QUANTITY_MISMATCH', 'QUALITY', 'COMPLAINT',
  'OTHER'] as const
export type IncidentKind = (typeof INCIDENT_KINDS)[number]

export type BenefitKind = (typeof BENEFIT.kinds)[number]
export type BenefitCertification = (typeof BENEFIT.certifications)[number]
