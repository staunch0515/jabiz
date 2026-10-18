import type { APIRequestContext } from '@playwright/test'
import { createHmac } from 'node:crypto'
import { adminToken, expect, insert, PASSWORD, rows, tokenOf, verifiedUser } from './support'

/*
 * Terms, privacy and help for the end-to-end tests (docs/quizbuks/plans/Q7-messages-legal.md, Q7b). Publishing a legal
 * text needs a content administrator with a verified address and a second factor every time: the tests make one of
 * their own, a verified user given the tests' role QB_E2E_CONTENT (which the end-to-end job adds to QB_ADMIN_ROLES, as
 * QB_E2E_FINANCE), who sets up TOTP and steps up through the API.
 */

export const CONTENT_ROLE = 'QB_E2E_CONTENT'
const CONTENT_PERMISSIONS = ['qb.admin.content.write', 'qb.admin.users.read']

const ROLES = 'urn:jabiz:dataset:platform:SecRole'
const ROLE_PERMISSIONS = 'urn:jabiz:dataset:platform:SecRolePermission'
const USERS = 'urn:jabiz:dataset:platform:SecUser'
const USER_ROLES = 'urn:jabiz:dataset:platform:SecUserRole'
export const DRAFTS = 'urn:jabiz:dataset:default:QbLegalDoc'
export const FAQ = 'urn:jabiz:dataset:default:QbFaqEntry'

export type LegalKind = 'TERMS_APP' | 'TERMS_SPONSOR' | 'PRIVACY' | 'COMMERCE_NOTICE' | 'AI_NOTICE'
export const APP_KINDS: LegalKind[] = ['TERMS_APP', 'PRIVACY']
export const SPONSOR_KINDS: LegalKind[] = ['TERMS_SPONSOR', 'PRIVACY']

/** RFC 6238 with the authenticator defaults (SHA-1, 6 digits, 30 s), as the server checks it. */
export function totp(secret: string, time = Date.now()): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = ''
  for (const char of secret.replace(/=+$/, '')) bits += alphabet.indexOf(char).toString(2).padStart(5, '0')
  const key = Buffer.from(bits.match(/.{8}/g)!.map((byte) => parseInt(byte, 2)))
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(time / 30_000)))
  const hash = createHmac('sha1', key).update(counter).digest()
  const offset = hash[hash.length - 1] & 0x0f
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0')
}

/** The tests' content administrator: signed in at the administration, with a second factor just passed. */
export async function legalAdmin(request: APIRequestContext): Promise<{ email: string; token: string }> {
  const email = await verifiedUser(request, 'sponsor', 'Lena')
  const admin = await adminToken(request)
  let roleId = (await rows(request, admin, ROLES, [{ field: 'roleCode', op: 'eq', value: CONTENT_ROLE }], 1))[0]?.id
  if (!roleId) {
    roleId = (await insert(request, admin, ROLES, { roleCode: CONTENT_ROLE, labels: { en: 'E2E content' },
      enabled: true })).id
  }
  const held = (await rows(request, admin, ROLE_PERMISSIONS, [{ field: 'roleId', op: 'eq', value: roleId }]))
    .map((row) => row.attributes.permission)
  for (const permission of CONTENT_PERMISSIONS.filter((p) => !held.includes(p))) {
    await insert(request, admin, ROLE_PERMISSIONS, { roleId, permission })
  }
  const userId = (await rows(request, admin, USERS, [{ field: 'userName', op: 'eq', value: email }], 1))[0].id
  await insert(request, admin, USER_ROLES, { userId, roleId })
  const signedIn = await tokenOf(request, email, PASSWORD, 'admin')
  const headers = { Authorization: `Bearer ${signedIn}` }
  const enrolled = await request.post('/api/auth/mfa/enroll', { headers })
  expect(enrolled.status(), await enrolled.text()).toBe(200)
  const secret = (await enrolled.json()).secret as string
  const confirmed = await request.post('/api/auth/mfa/enroll/confirm', { headers, data: { code: totp(secret) } })
  expect(confirmed.status(), await confirmed.text()).toBe(200)
  // The next time step's code (within the allowed drift): the confirming code does not work twice.
  const steppedUp = await request.post('/api/auth/step-up', { headers,
    data: { code: totp(secret, Date.now() + 30_000) } })
  expect(steppedUp.status(), await steppedUp.text()).toBe(200)
  return { email, token: (await steppedUp.json()).accessToken as string }
}

/** The draft of a kind: its key and its version (what an edit names). */
export async function draftOf(request: APIRequestContext, token: string, kind: LegalKind) {
  const found = await rows(request, token, DRAFTS, [{ field: 'kind', op: 'eq', value: kind }], 1)
  expect(found, `the draft of ${kind}`).toHaveLength(1)
  return found[0]
}

/**
 * Publishes a new version of a kind: the draft gets a text no earlier run used (still a marked test placeholder),
 * then `QB_LEGAL_PUBLISH`, in effect at once. Returns the version's number.
 */
export async function publishVersion(request: APIRequestContext, token: string, kind: LegalKind,
  requiresConsent: boolean, mark: string): Promise<number> {
  return (await publish(request, token, kind, requiresConsent, mark, null)).versionNo as number
}

/**
 * Announces a new version of a kind for a later time and returns `QB_LEGAL_PUBLISH`'s output (its `versionId` and
 * `versionNo`). A version an earlier run announced and left is withdrawn first, so that runs repeat.
 */
export async function announceVersion(request: APIRequestContext, token: string, kind: LegalKind, mark: string,
  effectiveAt: string): Promise<Record<string, unknown>> {
  const versions = await request.post('/api/queries/qb.admin.legal-versions', {
    headers: { Authorization: `Bearer ${token}` },
    data: { filters: [{ field: 'kind', op: 'eq', value: kind }], limit: 100 },
  })
  expect(versions.status(), await versions.text()).toBe(200)
  for (const row of (await versions.json()).items as Record<string, unknown>[]) {
    if (!row.withdrawn && Date.parse(String(row.effectiveAt)) > Date.now()) {
      await withdrawVersion(request, token, String(row.legalVersionId), 'Left by an earlier run')
    }
  }
  return publish(request, token, kind, true, mark, effectiveAt)
}

/** Withdraws an announced version (`QB_LEGAL_WITHDRAW`). */
export async function withdrawVersion(request: APIRequestContext, token: string, legalVersionId: string,
  reason: string) {
  const response = await request.post('/api/processes/QB_LEGAL_WITHDRAW/latest', {
    headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() },
    data: { legalVersionId, reason },
  })
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json()).output as Record<string, unknown>
}

async function publish(request: APIRequestContext, token: string, kind: LegalKind, requiresConsent: boolean,
  mark: string, effectiveAt: string | null): Promise<Record<string, unknown>> {
  const draft = await draftOf(request, token, kind)
  const text = (en: string, ja: string) => ({ en, ja })
  const edited = await request.post(`/api/datasets/${encodeURIComponent(DRAFTS)}/commit`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { changes: [{ action: 'UPDATE', id: draft.id, version: draft.version, attributes: {
      title: text(`[TEST PLACEHOLDER] ${kind}`, `[TEST PLACEHOLDER] ${kind}（日本語）`),
      body: text(`[TEST PLACEHOLDER] ${kind} — ${mark}`, `[TEST PLACEHOLDER] ${kind}（${mark}）`),
      changeSummary: text(`Changed for the tests: ${mark}`, `テストのための変更：${mark}`),
    } }] },
  })
  expect(edited.status(), await edited.text()).toBe(200)
  // The draft as edited: the version the publisher read (required, review L1).
  const read = await draftOf(request, token, kind)
  const response = await request.post('/api/processes/QB_LEGAL_PUBLISH/latest', {
    headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() },
    data: { docId: draft.id, requiresConsent, baseRevision: read.version, ...(effectiveAt ? { effectiveAt } : {}) },
  })
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json()).output as Record<string, unknown>
}

/** The texts in effect now (the public template, anonymous): the kinds that have one. */
export async function inEffect(request: APIRequestContext, kind: LegalKind) {
  const response = await request.get('/api/public/queries/qb.public.legal', {
    params: { 'p.kind': kind, 'p.at': new Date().toISOString() } })
  expect(response.status(), await response.text()).toBe(200)
  const row = ((await response.json()).items ?? [])[0]
  return row?.versionNo ? row as { versionNo: number } : null
}

let published = false

/**
 * The takers' terms, the sponsors' terms and the privacy policy in effect, as before going live: published once on a
 * new database (an earlier run's stay in effect). Every test then registers and applies as users do, accepting them.
 */
export async function termsInEffect(request: APIRequestContext) {
  if (published) return
  const missing: LegalKind[] = []
  for (const kind of ['TERMS_APP', 'TERMS_SPONSOR', 'PRIVACY'] as const) {
    if (!(await inEffect(request, kind))) missing.push(kind)
  }
  if (missing.length > 0) {
    const { token } = await legalAdmin(request)
    for (const kind of missing) await publishVersion(request, token, kind, true, `first ${Date.now()}`)
  }
  published = true
}
