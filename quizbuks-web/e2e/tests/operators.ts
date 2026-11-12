import type { APIRequestContext, Page } from '@playwright/test'
import { contentAdmin, totp } from './legal'
import { adminToken, expect, insert, PASSWORD, rows, run, tokenOf, unique, verifiedUser } from './support'
import { query, sponsor } from './content'

/*
 * Operators for the end-to-end tests (F-S1, docs/quizbuks/plans/impl/F-S1.md): every sponsor belongs to an operator.
 * An operator is created with its lead, whose account is the operator's own sponsor; the lead, registered at the sponsor
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
  /** The TOTP secret enrolled at the first sign-in, and when: a page's sign-in ({@link signInOperatorDesk}) needs them. */
  secret: string
  enrolledAt: number
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
  const session = await operatorSession(request, email)
  return { operatorId: String(saved.operatorId), userId, email, operatorToken: session.token, sponsorToken,
    secret: session.secret, enrolledAt: Date.now() }
}

/** A member of the operator holding QB_OPERATOR, signed in at the operators' entry; returns the session. */
async function newMember(request: APIRequestContext, operatorId: string, name: string): Promise<Member> {
  const email = await verifiedUser(request, 'sponsor', name)
  const admin = await adminToken(request)
  const userId = String((await rows(request, admin, USERS, [{ field: 'userName', op: 'eq', value: email }], 1))[0].id)
  const hq = await tokenOf(request, await contentAdmin(request), PASSWORD, 'admin')
  await run(request, hq, 'QB_OPERATOR_MEMBER_ADD', { operatorId, userId, lead: false })
  const roleId = (await rows(request, admin, ROLES, [{ field: 'roleCode', op: 'eq', value: 'QB_OPERATOR' }], 1))[0].id
  await insert(request, admin, USER_ROLES, { userId, roleId })
  return { email, ...await operatorSession(request, email) }
}

/** A member of an operator signed in at the operators' entry: the address, the TOTP secret and the session. */
interface Member extends OperatorSession {
  email: string
}

/**
 * One reviewer per operator for this worker (a run adds its own: the tests only add data). The reviewer is kept, not
 * its session: an access token lives jabiz.security.jwt.access-token-ttl (15 minutes) and a full run is longer, so a
 * session about to end is replaced by signing in again (with the reviewer's second factor).
 */
const reviewers = new Map<string, Promise<Member>>()

/** How long before its end an access token is no longer handed out. */
const TOKEN_MARGIN_MS = 2 * 60_000

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
  const member = await reviewer
  if (member.expiresAt - Date.now() > TOKEN_MARGIN_MS) return member.token
  const renewed = newSession(request, member)
  reviewers.set(operatorId, renewed)
  renewed.catch(() => reviewers.delete(operatorId))
  return (await renewed).token
}

/** The member signed in again, with the second factor enrolled at the first sign-in. */
async function newSession(request: APIRequestContext, member: Member): Promise<Member> {
  return { email: member.email, ...await operatorSession(request, member.email, member.secret) }
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

interface OperatorSession {
  /** The TOTP secret enrolled at the first sign-in. */
  secret: string
  token: string
  /** When the access token ends (its `exp`), in milliseconds. */
  expiresAt: number
}

/**
 * A session at the operators' entry: the first sign-in enrols TOTP; a later one (with the secret) passes the second
 * factor with the current code. Signing in again is for a session about to end, minutes after the last code, so the
 * code is not one used before.
 */
async function operatorSession(request: APIRequestContext, email: string, known?: string): Promise<OperatorSession> {
  let secret = known ?? ''
  const login = () => request.post('/api/auth/login', { data: { userName: email, password: PASSWORD,
    entry: 'operator' } })
  let response = await login()
  expect(response.status(), await response.text()).toBe(200)
  let body = await response.json()
  if (body.status === 'MFA_ENROLLMENT_REQUIRED') {
    const enrolled = await request.post('/api/auth/challenge/enroll', { data: { challenge: body.challenge } })
    expect(enrolled.status(), await enrolled.text()).toBe(200)
    secret = (await enrolled.json()).secret as string
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
  } else if (body.status === 'MFA_REQUIRED' && secret) {
    const verified = await request.post('/api/auth/challenge/verify', { data: { challenge: body.challenge,
      code: totp(secret) } })
    expect(verified.status(), await verified.text()).toBe(200)
    body = await verified.json()
  }
  expect(body.accessToken, JSON.stringify(body.status)).toBeTruthy()
  const token = body.accessToken as string
  const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')) as { exp: number }
  return { secret, token, expiresAt: claims.exp * 1000 }
}

/**
 * F-C10c: a new member of the operator that looks after the Sponsor, for a test that signs in at the Operator
 * Management System's page: the address and the TOTP secret enrolled (through the API, with the codes of two time
 * steps; a page's sign-in waits for the next step's code, {@link nextCode}).
 */
export async function operatorMemberFor(request: APIRequestContext, sponsorToken: string, name = 'Helper') {
  const [own] = await query(request, sponsorToken, 'qb.sponsor.operator', {})
  expect(own, 'the Sponsor has an operator').toBeTruthy()
  const member = await newMember(request, String(own.operatorId), name)
  return { email: member.email, secret: member.secret, enrolledAt: Date.now() }
}

/**
 * A TOTP code no sign-in used yet: enrolment used the codes of its time step and the next, so this waits until the
 * clock is in that next step and gives the one after (the server allows a step of drift).
 */
export async function nextCode(secret: string, enrolledAt: number): Promise<string> {
  const step = (time: number) => Math.floor(time / 30_000)
  const wait = (step(enrolledAt) + 1) * 30_000 - Date.now() + 500
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait))
  return totp(secret, Date.now() + 30_000)
}

/**
 * Signs a page in at the Operator Management System as an enrolled member (the lead of {@link operatorWithLead}, or
 * {@link operatorMemberFor}'s member): the address and password, then the second factor's next unused code.
 */
export async function signInOperatorDesk(page: Page, member: { email: string; secret: string; enrolledAt: number }) {
  await page.goto('/operator/login')
  await page.getByPlaceholder('User name', { exact: true }).fill(member.email)
  await page.getByPlaceholder('Password', { exact: true }).fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  const code = page.locator('input[autocomplete="one-time-code"]')
  await code.fill(await nextCode(member.secret, member.enrolledAt))
  await code.press('Enter')
  await expect(page).not.toHaveURL(/\/login/)
}

/**
 * An approved Sponsor of the operator (not of the default one): one signs up with the default operator, which approves
 * the application, and the platform's content administrator then moves the Sponsor to the operator (an account must
 * exist to be moved). The session is signed in again after the move, as the Sponsor's operator is read from the
 * token's account.
 */
export async function sponsorOfOperator(request: APIRequestContext, operator: OperatorLead, name: string,
  company: string) {
  const made = await sponsor(request, name)
  const applied = await run(request, made.token, 'QB_SPONSOR_APPLY', { displayName: name, company,
    acceptTerms: true })
  if (applied.approval === 'PENDING') await decideAsOperator(request, made.token, applied.approvalRequestId)
  await expect.poll(async () => (await rows(request, made.token, 'urn:jabiz:dataset:sponsor:QbSponsor', []))[0]
    ?.attributes.reviewStatus, { message: 'the Sponsor is approved', timeout: 30_000 }).toBe('APPROVED')
  const me = await (await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${made.token}` } })).json()
  const userId = String(me.userId)
  const hq = await tokenOf(request, await contentAdmin(request), PASSWORD, 'admin')
  await run(request, hq, 'QB_SPONSOR_OPERATOR_ASSIGN', { userId, operatorId: operator.operatorId,
    reason: 'End-to-end test' })
  return { email: made.email, token: await tokenOf(request, made.email, PASSWORD, 'sponsor'), userId, company }
}
