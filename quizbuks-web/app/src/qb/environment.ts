import type { Environment, EnvironmentOverride } from '@jabiz/client'
import { BRAND } from './brand'

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
 * The environment the pages show on the brand's own site (platform 17ze, `configureClient({ environment })`): the
 * brand profile's `siteUrl` host and its `www.` are never labelled a test system, even while that address is answered
 * by the staging application until production exists (deploy/quizbuks/DEPLOY.md, "上线前 quizbuks.com 与 staging
 * 共用": Google AdSense reviews it). Every page follows it: the app's own frames, the sign-in pages and the sponsor
 * console's shell. Any other address (staging's own, local, the tests) shows what the server says; null without a
 * usable site address.
 */
export function siteEnvironment(siteUrl: string = BRAND.siteUrl): EnvironmentOverride | null {
  const hosts = brandHosts(siteUrl)
  if (hosts.length === 0) return null
  return (_server, location) => (hosts.includes(location.hostname.toLowerCase()) ? PRODUCTION : undefined)
}
