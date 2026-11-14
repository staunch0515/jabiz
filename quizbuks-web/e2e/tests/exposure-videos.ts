import type { APIRequestContext } from '@playwright/test'
import { query } from './content'
import { contentAdmin } from './legal'
import { reviewerFor } from './operators'
import { approvedSponsor } from './publishing'
import { expect, PASSWORD, run, tokenOf } from './support'

/*
 * The shared videos of F-S13's end-to-end tests, which F-M8d's step of the closed loop (launch-loop.spec) uses too: a
 * provider's vertical and horizontal video links, shared and approved by its operator.
 */

export const VIDEO_HOST = 'https://e2e-video.quizbuks.test'

/** A word list code no earlier run used (the list only grows), as topics.spec makes them. */
export function newCode(): string {
  const countdown = (36 ** 9 - Date.now()).toString(36).padStart(9, '0')
  return `0e2e-${countdown}${Math.floor(Math.random() * 36 ** 2).toString(36).padStart(2, '0')}`
}

/** The provider's two video links, shared (vertical and horizontal) and approved by its operator. */
export async function sharedVideos(request: APIRequestContext, mark: string) {
  const hq = await tokenOf(request, await contentAdmin(request, 'Hana'), PASSWORD, 'admin')
  const code = newCode()
  await run(request, hq, 'QB_TOPIC_ADD', { topicId: code, nameZh: `宠物 ${mark}`, nameJa: `ペット ${mark}`,
    nameEn: `Pets ${mark}` })
  const provider = await approvedSponsor(request, 'Pavo')
  const share = async (vertical: boolean) => {
    const asset = await run(request, provider.token, 'QB_ASSET_SAVE', { kind: 'VIDEO_LINK',
      name: `Cats ${vertical ? 'tall' : 'wide'} ${mark}`, language: 'en', tags: [], confirmed: [],
      url: `${VIDEO_HOST}/${vertical ? 'tall' : 'wide'}/${encodeURIComponent(mark)}`, durationSec: 95 })
    const shared = await run(request, provider.token, 'QB_SHARED_VIDEO_SHARE', { assetId: asset.assetId,
      titleZh: `猫 ${mark}`, titleJa: `猫 ${mark}`, titleEn: `Cats ${mark}`, topics: [code], vertical })
    await run(request, await reviewerFor(request, provider.token), 'APPROVAL_DECIDE', {
      requestId: shared.approvalRequestId, decision: 'APPROVE' })
    const sharedVideoId = String(shared.sharedVideoId)
    await expect.poll(async () => (await query(request, provider.token, 'qb.sponsor.my-shared-videos', {}))
      .find((row) => row.sharedVideoId === sharedVideoId)?.status, { message: 'the share is approved',
      timeout: 30_000 }).toBe('APPROVED')
    return sharedVideoId
  }
  return { hq, vertical: await share(true), horizontal: await share(false) }
}

