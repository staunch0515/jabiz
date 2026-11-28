import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { Page } from '@playwright/test'

/*
 * F-S15: a video file on an https address, answered by the test (no video server is reached). The file is a real
 * ten-second video (VP8 + Opus, `media/quizbuks-demo-10s.webm`): Playwright's open-source Chromium has no H.264.
 */
export const VIDEO_FILE = 'https://videos.e2e.quizbuks.test/quizbuks-demo-10s.webm'
/** The same video at a second address: a second post's file (the stage takes it as another player). */
export const SECOND_VIDEO_FILE = 'https://videos.e2e.quizbuks.test/quizbuks-demo-10s-second.webm'
export const MISSING_VIDEO_FILE = 'https://videos.e2e.quizbuks.test/missing.webm'
const VIDEO = readFileSync(fileURLToPath(new URL('./media/quizbuks-demo-10s.webm', import.meta.url)))

/** The video server: the file, with byte ranges as a real one answers them (browsers ask for ranges of media). */
export async function videoServer(page: Page) {
  const serve = (route: Parameters<Parameters<Page['route']>[1]>[0]) => {
    const range = /bytes=(\d+)-(\d*)/.exec(route.request().headers().range ?? '')
    if (!range) {
      return route.fulfill({ status: 200, contentType: 'video/webm', body: VIDEO,
        headers: { 'Accept-Ranges': 'bytes' } })
    }
    const from = Number(range[1])
    const to = range[2] ? Math.min(Number(range[2]), VIDEO.length - 1) : VIDEO.length - 1
    return route.fulfill({ status: 206, contentType: 'video/webm', body: VIDEO.subarray(from, to + 1),
      headers: { 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${from}-${to}/${VIDEO.length}` } })
  }
  await page.route(VIDEO_FILE, serve)
  await page.route(SECOND_VIDEO_FILE, serve)
  await page.route(MISSING_VIDEO_FILE, (route) => route.fulfill({ status: 404, contentType: 'text/plain',
    body: 'Not found' }))
}
