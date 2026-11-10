import { runProcess, runQuery } from '@jabiz/client'
import { profilePath } from '../profile/paths'

/** The Sponsor's company verification (F-M3): its template and processes. */
export const VERIFICATION_TEMPLATE = 'qb.sponsor.verification'
export const VERIFICATION_PROCESS = {
  submit: 'QB_SPONSOR_VERIFICATION_SUBMIT',
  confirm: 'QB_SPONSOR_VERIFICATION_CONFIRM',
} as const
export const VERIFICATION_QUERY = ['qb', 'sponsor', 'verification'] as const

/** Where the verification is, and where the mailed link lands (`?token=`). */
export const VERIFICATION_PATH = profilePath('verification')
export const VERIFICATION_CONFIRM_PATH = `${VERIFICATION_PATH}/confirm`

export type VerificationStatus = 'LINK_SENT' | 'REVIEW' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'SUPERSEDED' | 'REVOKED'

/** The Sponsor's verification as `qb.sponsor.verification` gives it (always one row; none submitted: no id). */
export interface Verification {
  verificationId: string | null
  companyName: string | null
  domain: string | null
  method: 'ACCOUNT' | 'LINK' | null
  confirmAddress: string | null
  status: VerificationStatus | null
  linkSentAt: string | null
  linkExpiresAt: string | null
  submittedAt: string | null
  verifiedAt: string | null
  rejectReason: string | null
  revokeReason: string | null
  endedAt: string | null
  /** Verified now (the one definition: a submission approved). */
  verified: boolean
  /** The onboarding platform fee Kudos granted (none yet: null), the certified amount, and whether topped up. */
  onboardingAmount: number | null
  certifiedAmount: number | null
  toppedUp: boolean
}

type Row = Record<string, unknown>

const text = (value: unknown) => (value == null || value === '' ? null : String(value))
const amount = (value: unknown) => (value == null || value === '' ? null : Number(value))

export function verificationOf(row: Row | undefined): Verification {
  const r = row ?? {}
  return {
    verificationId: text(r.verificationId),
    companyName: text(r.companyName),
    domain: text(r.domain),
    method: r.method === 'ACCOUNT' || r.method === 'LINK' ? r.method : null,
    confirmAddress: text(r.confirmAddress),
    status: (text(r.status) as VerificationStatus | null),
    linkSentAt: text(r.linkSentAt),
    linkExpiresAt: text(r.linkExpiresAt),
    submittedAt: text(r.submittedAt),
    verifiedAt: text(r.verifiedAt),
    rejectReason: text(r.rejectReason),
    revokeReason: text(r.revokeReason),
    endedAt: text(r.endedAt),
    verified: r.verified === true,
    onboardingAmount: amount(r.onboardingAmount),
    certifiedAmount: amount(r.certifiedAmount),
    toppedUp: r.toppedUp === true,
  }
}

export async function readVerification(): Promise<Verification> {
  const page = await runQuery<Row>(VERIFICATION_TEMPLATE, { params: {}, limit: 1 })
  return verificationOf(page.items[0])
}

/** What a top-up a verification would bring: the certified amount less the onboarding granted; none otherwise. */
export function topUpOf(verification: Verification): number | null {
  if (verification.verified || verification.toppedUp || verification.onboardingAmount == null
    || verification.certifiedAmount == null) return null
  const difference = verification.certifiedAmount - verification.onboardingAmount
  return difference > 0 ? difference : null
}

/** The domain of a website address, for the form's default (none for anything that is not a web address). */
export function domainOfWebsite(website: string | null | undefined): string {
  if (!website) return ''
  try {
    const host = new URL(website).hostname.toLowerCase()
    return host.startsWith('www.') ? host.slice(4) : host
  } catch {
    return ''
  }
}

export interface SubmitInput {
  companyName: string
  domain: string
  confirmAddress: string | null
}

export interface VerificationOutput {
  verificationId: string
  status: VerificationStatus
  method: 'ACCOUNT' | 'LINK'
  approvalRequestId: string | null
}

export const submitVerification = (input: SubmitInput) =>
  runProcess<VerificationOutput>(VERIFICATION_PROCESS.submit, { ...input })

export const confirmVerification = (token: string) =>
  runProcess<VerificationOutput>(VERIFICATION_PROCESS.confirm, { token })
