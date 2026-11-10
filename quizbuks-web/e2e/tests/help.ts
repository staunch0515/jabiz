import type { APIRequestContext } from '@playwright/test'
import { contentAdmin, FAQ } from './legal'
import { adminToken, expect, insert, PASSWORD, rows, run, setControlledParam, tokenOf, unique } from './support'

/*
 * The AI help for the end-to-end tests (F-C10, docs/quizbuks/plans/impl/F-C10.md): the switch on (four eyes), the usage
 * manuals published by a content administrator, and a FAQ entry in the materials the help answers from. The provider
 * is the local Claude stub (tools/quizbuks/claude-stub): it answers a question that is a FAQ heading of the materials
 * sent with it with that entry's answer and its site paths.
 */

const MANUALS = 'urn:jabiz:dataset:default:QbHelpManual'
const MATERIALS = 'urn:jabiz:dataset:default:QbHelpMaterial'

let switchedOn = false

/** `qb.help.enabled` on (once a worker; the change stays, as every controlled change of the tests). */
export async function helpOn(request: APIRequestContext) {
  if (switchedOn) return
  await setControlledParam(request, 'qb.help.enabled', 'true')
  switchedOn = true
}

/** A session of the tests' content administrator at headquarters' Management System (the help desk too). */
export async function contentToken(request: APIRequestContext): Promise<{ email: string; token: string }> {
  const email = await contentAdmin(request, 'Hana')
  return { email, token: await tokenOf(request, email, PASSWORD, 'admin') }
}

/**
 * A published FAQ entry of the Takers' with an answer that names a page of the app, the manuals published, and the
 * Takers' English materials rebuilt with it (waited for). Returns the question.
 */
export async function faqInMaterials(request: APIRequestContext, token: string): Promise<string> {
  const question = unique('How do I see my Kudos ')
  await insert(request, await adminToken(request), FAQ, { audience: 'APP', category: 'REWARDS',
    question: { en: question }, answer: { en: 'Open My Kudos at /taker/wallet to see every Kudos you earned.' },
    seq: 1, published: true })
  const since = Date.now()
  // Publishing each manual (as read) rebuilds every snapshot; so does the FAQ change just made.
  for (const manual of await rows(request, token, MANUALS, [])) {
    await run(request, token, 'QB_HELP_PUBLISH', { manualId: manual.id, baseRevision: manual.version })
  }
  await expect.poll(async () => {
    const [material] = await rows(request, token, MATERIALS, [{ field: 'entry', op: 'eq', value: 'app' },
      { field: 'language', op: 'eq', value: 'en' }], 1)
    const at = material?.attributes
    return !!at && !at.pendingHash && at.contentHash === at.latestHash && Date.parse(String(at.latestAt)) >= since - 1000
  }, { message: 'the Takers\' English materials hold the new FAQ entry', timeout: 90_000, intervals: [1_000] })
    .toBe(true)
  return question
}
