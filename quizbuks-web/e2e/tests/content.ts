import type { APIRequestContext } from '@playwright/test'
import zlib from 'node:zlib'
import { expect, PASSWORD, run, tokenOf, unique, verifiedUser } from './support'

/*
 * Quiz content for the end-to-end tests, through the API as the sponsor console calls it (docs/quizbuks/plans/
 * Q3-content.md): files under the content policies, a quiz with questions and a saved version. The publishing tests
 * start from a quiz made here.
 */

/** A PNG of `size` x `size` pixels in one colour; the server re-encodes it like any image. */
export function png(size = 16, rgb: [number, number, number] = [180, 120, 60]): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const body = Buffer.concat([Buffer.from(type, 'latin1'), data])
    const length = Buffer.alloc(4)
    length.writeUInt32BE(data.length)
    const crc = Buffer.alloc(4)
    crc.writeUInt32BE(zlib.crc32(body))
    return Buffer.concat([length, body, crc])
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(size, 0)
  header.writeUInt32BE(size, 4)
  header.set([8, 2, 0, 0, 0], 8)
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: size }, () => rgb).flat())])
  const pixels = zlib.deflateSync(Buffer.concat(Array.from({ length: size }, () => row)))
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header),
    chunk('IDAT', pixels), chunk('IEND', Buffer.alloc(0))])
}

export const PNG = png()

/** A one-page PDF. */
export const PDF = Buffer.from(`%PDF-1.4
1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj
xref
0 4
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
trailer << /Size 4 /Root 1 0 R >>
startxref
186
%%EOF
`)

/** The content file policies (backend/quizbuks `content.ContentConfig`). */
export const POLICY = { image: 'qb.content.image', pdf: 'qb.content.pdf', audio: 'qb.content.audio' } as const

/** A sponsor who registered and verified the address (not yet approved: editing content needs no approval). */
export async function sponsor(request: APIRequestContext, name = 'Quinn') {
  const email = await verifiedUser(request, 'sponsor', name)
  const token = await tokenOf(request, email, PASSWORD, 'sponsor')
  return { email, token }
}

/** Uploads a file under a policy as the token's user; returns its id. */
export async function upload(request: APIRequestContext, token: string, policy: string, name: string,
  mimeType: string, buffer: Buffer): Promise<string> {
  const response = await request.post(`/api/files?policy=${encodeURIComponent(policy)}`, {
    headers: { Authorization: `Bearer ${token}` },
    multipart: { file: { name, mimeType, buffer } },
  })
  expect(response.status(), await response.text()).toBeLessThan(300)
  return (await response.json()).fileId as string
}

/** One page of a SQL template's rows. */
export async function query(request: APIRequestContext, token: string, id: string, params: Record<string, unknown>) {
  const response = await request.post(`/api/queries/${encodeURIComponent(id)}`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { params, limit: 100 },
  })
  expect(response.status(), await response.text()).toBe(200)
  return ((await response.json()).items ?? []) as Record<string, unknown>[]
}

export interface QuizMade {
  quizId: string
  title: string
  versionNo: number
  label: string
  revision: number
}

/**
 * A complete quiz with a saved version, made through the processes the console calls: a cover, `questions`
 * questions of two options (the first correct, worth 1 point each) and an article. Returns it with its version.
 */
export async function completeQuiz(request: APIRequestContext, token: string,
  options: { title?: string; questions?: number } = {}): Promise<QuizMade> {
  const title = options.title ?? unique('Quiz ')
  const cover = await upload(request, token, POLICY.image, 'cover.png', 'image/png', PNG)
  const quiz = await run(request, token, 'QB_QUIZ_SAVE', { title, intro: 'An **e2e** quiz.', cover, timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  for (let n = 1; n <= (options.questions ?? 2); n++) {
    await run(request, token, 'QB_QUESTION_SAVE', { quizId, stem: `Question ${n}?`, points: 1, options: [
      { text: `Right ${n}`, correct: true }, { text: `Wrong ${n}`, correct: false }] })
  }
  await run(request, token, 'QB_MATERIAL_SAVE', { quizId, kind: 'ARTICLE', title: 'Read first',
    description: 'Everything you need.', body: '# Notes\n\nThe right answers start with *Right*.' })
  const version = await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  return { quizId, title, versionNo: version.versionNo as number, label: version.label as string,
    revision: version.revision as number }
}
