import type { APIRequestContext } from '@playwright/test'
import { adminToken, rows, setControlledParam } from './support'

/*
 * A small sample Sponsor's package for the end-to-end tests (F-F11c, docs/quizbuks/plans/impl/F-F11.md section 6.3): the
 * test-side counterpart of the backend's `PackageFixtures`, in the format the Sponsor page reads (package.ts of the
 * Sponsor app, format version 2). It is made here, not kept as a file, so that the bank can be as long as the
 * deployment's import batch needs (a second batch) whatever that limit is. Test-only.
 */

/** The video the second quiz shows: a YouTube one, which mainland China cannot play (F-S11). */
export const SAMPLE_VIDEO = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'

/** How many questions a Taker is given from the long bank (the publication's `pickCount`). */
export const SAMPLE_PICK = 5

/** The names a test finds the package's parts by. */
export interface SampleNames {
  bankTitle: string
  videoTitle: string
  mapName: string
  node: string
  boundName: string
  prizeName: string
}

const SETTINGS_FORMAT = 'quizbuks.publication-settings'

function quizFile(title: string, count: number, materials: Record<string, unknown>[] = []) {
  return { format: 'quizbuks.quiz', formatVersion: 1, exportedAt: '2026-11-12T00:00:00Z',
    quiz: { kind: 'QUIZ', title, timeLimitSec: 300 }, materials,
    questions: Array.from({ length: count }, (_, i) => ({ stem: `${title} ${i + 1}: which is right?`, points: 1,
      options: [{ text: 'Right', correct: true }, { text: 'Wrong' }] })),
    files: {} }
}

/** The settings of a publication as `settingsOf` writes them (package.ts), with what the sample varies. */
function settings(name: string, more: Record<string, unknown>) {
  return { format: SETTINGS_FORMAT, version: 1, name, fullScore: null, durationDays: 7, timeLimitSec: 300,
    passScore: null, pickCount: null, entryFee: 0, budgetCap: null, maxWinners: null, tiers: [],
    rewardMethod: 'PLATFORM', redeemDescription: null, digitalDelivery: null, digitalDescription: null,
    digitalSendDays: null, audience: 'PUBLIC', regions: ['GLOBAL'], referral: null, languages: [], translations: {},
    conditions: [], ...more }
}

/**
 * The package: a long bank of `importBatch + 3` questions (so that its import goes on to a second batch) used by a
 * digital prize publication that draws {@link SAMPLE_PICK} of them; a quiz of two questions with a YouTube
 * material, used by a publication bound to the node of the package's own map. The prize codes are not in a package,
 * and the times are chosen at the import.
 */
export function samplePackage(importBatch: number, names: SampleNames): Buffer {
  const bank = quizFile(names.bankTitle, importBatch + 3)
  const video = quizFile(names.videoTitle, 2, [{ kind: 'VIDEO_LINK', title: 'Film', description: null, body: null,
    url: SAMPLE_VIDEO, pdf: null, audio: null, images: [] }])
  return Buffer.from(JSON.stringify({ format: 'quizbuks.sponsor-package', version: 2,
    exportedAt: '2026-11-12T00:00:00Z', profile: null,
    trees: [{ key: 't1', name: { en: names.mapName }, description: null, nodes: [{ key: 'basics',
      name: { en: names.node }, description: null, condition: { op: 'ALL', items: [{ passed: true }] } }] }],
    quizzes: [
      { key: 'q1', current: null, versions: [{ key: 'q1v1', label: 'v1.0', file: bank }] },
      { key: 'q2', current: null, versions: [{ key: 'q2v1', label: 'v1.0', file: video }] },
    ],
    publications: [
      { quiz: 'q2', version: 'q2v1', node: { tree: 't1', node: 'basics', name: { en: names.node } },
        settings: settings(names.boundName, { fullScore: 2, passScore: 2, budgetCap: 500, maxWinners: 1000,
          tiers: [{ fromScore: 0, toScore: 1, reward: 0 }, { fromScore: 2, toScore: 2, reward: 0 }] }) },
      { quiz: 'q1', version: 'q1v1',
        settings: settings(names.prizeName, { fullScore: SAMPLE_PICK, pickCount: SAMPLE_PICK, passScore: 3,
          maxWinners: 2, rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'POOL',
          digitalDescription: 'A coffee gift code',
          tiers: [{ fromScore: 0, toScore: 2, reward: 0 }, { fromScore: 3, toScore: SAMPLE_PICK, reward: 300 }] }) },
    ] }))
}

/** A controlled parameter as it stands now: its value, or none while nobody has set it. */
async function valueOf(request: APIRequestContext, key: string): Promise<string | null> {
  const [found] = await rows(request, await adminToken(request), 'urn:jabiz:dataset:platform:SysParam',
    [{ field: 'paramKey', op: 'eq', value: key }], 1)
  return found ? String(found.attributes.value) : null
}

/**
 * Makes sure the onboarding Kudos can be granted (`qb.fee-credit.grant.enabled`, and an amount to grant), changing
 * only what is not so already; returns what puts the changed ones back (call it in a `finally`). The parameters are
 * global, so a test that changes them is in a serial describe: no other test reads them meanwhile.
 */
export async function ensureOnboardingGrant(request: APIRequestContext): Promise<() => Promise<void>> {
  const kind = { type: 'monetary', currency: 'JPY', scale: 0 }
  const enabled = await valueOf(request, 'qb.fee-credit.grant.enabled')
  const basic = await valueOf(request, 'qb.fee-credit.grant.basic')
  const undo: (() => Promise<void>)[] = []
  if (enabled !== 'true') {
    await setControlledParam(request, 'qb.fee-credit.grant.enabled', 'true')
    undo.push(() => setControlledParam(request, 'qb.fee-credit.grant.enabled', enabled ?? 'false'))
  }
  if (basic === null || !(Number(basic) > 0)) {
    await setControlledParam(request, 'qb.fee-credit.grant.basic', '500', kind)
    undo.push(() => setControlledParam(request, 'qb.fee-credit.grant.basic', basic ?? '0', kind))
  }
  return async () => {
    for (const back of undo) await back()
  }
}
