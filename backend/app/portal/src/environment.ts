import type { Environment, EnvironmentOverride } from '@jabiz/client'

const PRODUCTION: Environment = { name: 'production', production: true }

/**
 * The demonstration of phase 17ze: a public address answered for a while by a test system's application is not
 * labelled a test system there. The host is a build variable (`VITE_PORTAL_PUBLIC_HOST`, unset in the demonstration,
 * so nothing changes); on any other host the pages show what the server says.
 */
export function portalEnvironment(publicHost: string | undefined): EnvironmentOverride | null {
  const host = publicHost?.trim().toLowerCase()
  if (!host) return null
  return (_server, location) => (location.hostname.toLowerCase() === host ? PRODUCTION : undefined)
}
