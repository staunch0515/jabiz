import type { APIRequestContext } from '@playwright/test'
import { contentAdmin, totp } from './legal'
import { adminToken, expect, insert, PASSWORD, rows, run, tokenOf, unique, verifiedUser } from './support'
import { query } from './content'

/*
 * Operators for the end-to-end tests (F-S1, docs/quizbuks/plans/impl/F-S1.md): every sponsor belongs to an operator,
 * and the operators run the sponsors' certifications. An operator is created with its lead, whose account is the
 * operator's own sponsor (the official publisher of its certification rounds); the lead, registered at the sponsor
 * entry, holds the sponsor role already. The role QB_OPERATOR asks for a second factor: the lead enrols TOTP at the
 * first sign-in at the operators' entry.
 */

const ROLES = 'urn:jabiz:dataset:platform:SecRole'
const USERS = 'urn:jabiz:dataset:platform:SecUser'
const USER_ROLES = 'urn:jabiz:dataset:platform:SecUserRole'

export interface OperatorLead {
  operatorId: string
  userId: string
  email: string
  /** A session at the operators' entry, in the operator's scope. */
  operatorToken: string
  /** A session at the sponsor entry: the operator's own sponsor. */
  sponsorToken: string
}

/** A new operator and its lead, signed in at both entries. */
export async function operatorWithLead(request: APIRequestContext, name = 'Ope'): Promise<OperatorLead> {
  const email = await verifiedUser(request, 'sponsor', name)
  const admin = await adminToken(request)
  const userId = String((await rows(request, admin, USERS, [{ field: 'userName', op: 'eq', value: email }], 1))[0].id)
  // QuizBuks's writes ask for a verified address, which the first administrator has not: the tests' content
  // administrator creates operators (its role holds qb.admin.operators.write).
  const hq = await tokenOf(request, await contentAdmin(request), PASSWORD, 'admin')
  const saved = await run(request, hq, 'QB_OPERATOR_SAVE', { name: unique(`${name} operator `), contactName: name,
    contactEmail: email, leadUserId: userId })
  // The sponsor session first: once the lead holds QB_OPERATOR (a role that asks for a second factor), signing in at
  // the sponsor entry asks for it too and gives no access token.
  const sponsorToken = await tokenOf(request, email, PASSWORD, 'sponsor')
  expect(sponsorToken, 'a session at the sponsor entry').toBeTruthy()
  const roleId = (await rows(request, admin, ROLES, [{ field: 'roleCode', op: 'eq', value: 'QB_OPERATOR' }], 1))[0].id
  await insert(request, admin, USER_ROLES, { userId, roleId })
  return { operatorId: String(saved.operatorId), userId, email, operatorToken: await operatorSignIn(request, email),
    sponsorToken }
}

/** A member of the operator holding QB_OPERATOR, signed in at the operators' entry; returns the session. */
async function newMember(request: APIRequestContext, operatorId: string, name: string): Promise<string> {
  const email = await verifiedUser(request, 'sponsor', name)
  const admin = await adminToken(request)
  const userId = String((await rows(request, admin, USERS, [{ field: 'userName', op: 'eq', value: email }], 1))[0].id)
  const hq = await tokenOf(request, await contentAdmin(request), PASSWORD, 'admin')
  await run(request, hq, 'QB_OPERATOR_MEMBER_ADD', { operatorId, userId, lead: false })
  const roleId = (await rows(request, admin, ROLES, [{ field: 'roleCode', op: 'eq', value: 'QB_OPERATOR' }], 1))[0].id
  await insert(request, admin, USER_ROLES, { userId, roleId })
  return operatorSignIn(request, email)
}

/** One reviewer per operator for this worker (a run adds its own: the tests only add data). */
const reviewers = new Map<string, Promise<string>>()

/**
 * A session of a member of the operator that looks after the Sponsor (F-S1: reviews are the operators', decided in
 * the Operator Management System), found as the Sponsor app finds "your operator" (`qb.sponsor.operator`). The member
 * is not the operator's lead, so the lead's own publications are reviewed by another member, as in production.
 */
export async function reviewerFor(request: APIRequestContext, sponsorToken: string): Promise<string> {
  const [own] = await query(request, sponsorToken, 'qb.sponsor.operator', {})
  expect(own, 'the Sponsor has an operator').toBeTruthy()
  const operatorId = String(own.operatorId)
  let reviewer = reviewers.get(operatorId)
  if (!reviewer) {
    reviewer = newMember(request, operatorId, 'Reviewer')
    reviewers.set(operatorId, reviewer)
    reviewer.catch(() => reviewers.delete(operatorId))
  }
  return reviewer
}

/** The operator's member decides the Sponsor's request, as the review pages of the Operator Management System do. */
export async function decideAsOperator(request: APIRequestContext, sponsorToken: string, requestId: unknown,
  decision: 'APPROVE' | 'REJECT' = 'APPROVE', reason: string | null = null) {
  return run(request, await reviewerFor(request, sponsorToken), 'APPROVAL_DECIDE', { requestId, decision, reason })
}

const OPERATORS = 'urn:jabiz:dataset:default:QbOperator'

/**
 * The default operator, which the sponsors who sign up by themselves belong to (F-S1): the platform's administrator
 * creates it after installing, with its lead (its own sponsor). Created once; later runs find it.
 */
export async function ensureDefaultOperator(request: APIRequestContext): Promise<string> {
  const admin = await adminToken(request)
  const found = await rows(request, admin, OPERATORS, [{ field: 'defaultForNew', op: 'eq', value: true }], 1)
  if (found.length > 0) return String(found[0].id)
  const email = await verifiedUser(request, 'sponsor', 'Default lead')
  const userId = String((await rows(request, admin, USERS, [{ field: 'userName', op: 'eq', value: email }], 1))[0].id)
  const hq = await tokenOf(request, await contentAdmin(request), PASSWORD, 'admin')
  const saved = await run(request, hq, 'QB_OPERATOR_SAVE', { name: 'QuizBuks', contactName: 'QuizBuks',
    contactEmail: email, leadUserId: userId, defaultForNew: true })
  return String(saved.operatorId)
}

/** Signs in at the operators' entry, enrolling TOTP the first time (the role requires a second factor). */
async function operatorSignIn(request: APIRequestContext, email: string): Promise<string> {
  const login = () => request.post('/api/auth/login', { data: { userName: email, password: PASSWORD,
    entry: 'operator' } })
  let response = await login()
  expect(response.status(), await response.text()).toBe(200)
  let body = await response.json()
  if (body.status === 'MFA_ENROLLMENT_REQUIRED') {
    const enrolled = await request.post('/api/auth/challenge/enroll', { data: { challenge: body.challenge } })
    expect(enrolled.status(), await enrolled.text()).toBe(200)
    const secret = (await enrolled.json()).secret as string
    const confirmed = await request.post('/api/auth/challenge/enroll/confirm', { data: { challenge: body.challenge,
      code: totp(secret) } })
    expect(confirmed.status(), await confirmed.text()).toBe(200)
    response = await login()
    body = await response.json()
    // The next time step's code (within the allowed drift): the confirming code does not work twice.
    const verified = await request.post('/api/auth/challenge/verify', { data: { challenge: body.challenge,
      code: totp(secret, Date.now() + 30_000) } })
    expect(verified.status(), await verified.text()).toBe(200)
    body = await verified.json()
  }
  expect(body.accessToken, JSON.stringify(body.status)).toBeTruthy()
  return body.accessToken as string
}
