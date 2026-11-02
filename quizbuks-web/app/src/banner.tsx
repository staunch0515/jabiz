import { EnvironmentBanner } from '@jabiz/ui'
import type { Environment } from '@jabiz/client'
import { BRAND } from './qb/brand'

/**
 * The test system's banner in the app's narrow frame (Q34): small type on one line, the explanation cut with an
 * ellipsis rather than wrapped, so the stripe never takes two lines of a phone's (or the phone-sized window's) height.
 * The environment's name, the part that matters, always shows in full.
 */
export const ONE_LINE_BANNER = 'py-1 text-xs whitespace-nowrap [&>span:last-child]:min-w-0 [&>span:last-child]:truncate'

/** The brand's own site as the real one: its host and its `www.`; none when the profile has no site address. */
function brandHosts(siteUrl: string): string[] {
  try {
    const host = new URL(siteUrl).hostname.toLowerCase().replace(/^www\./, '')
    return host ? [host, `www.${host}`] : []
  } catch {
    return []
  }
}

const PRODUCTION: Environment = { name: 'production', production: true }

/**
 * The environment the app's banner shows on this address: the brand's own site (`quizbuks.com`, from the brand
 * profile's `siteUrl`) is never labelled a test system, even while it is answered by the staging application until
 * production exists (deploy/quizbuks/DEPLOY.md, "上线前 quizbuks.com 与 staging 共用": Google AdSense reviews that
 * address). Any other address (staging's own, local, the tests) shows what the server says.
 */
export function bannerEnvironment(hostname: string = window.location.hostname,
  siteUrl: string = BRAND.siteUrl): Environment | undefined {
  return brandHosts(siteUrl).includes(hostname.toLowerCase()) ? PRODUCTION : undefined
}

/** The platform's banner as the app places it (one line), with the address rule above. */
export function AppEnvironmentBanner() {
  return <EnvironmentBanner className={ONE_LINE_BANNER} environment={bannerEnvironment()} />
}
