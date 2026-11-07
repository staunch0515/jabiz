import type { components } from '../api/schema'

/** Where the caller is by its own address (`GET /api/client/region`, decision D74). */
export type ClientRegion = components['schemas']['ClientRegion']

const UNKNOWN: ClientRegion = { country: undefined, mainlandChina: false, known: false }

/**
 * Asks the server where this browser's requests come from (decision D74): the country of the client address behind
 * the trusted proxies, from the server's local address database. Never cached (the server says `no-store`, and this
 * asks with `cache: 'no-store'`): a network change, a VPN switched on, gives a new answer. Without credentials, as
 * it concerns only the caller's own address. A failure answers "unknown"; what unknown means is the page's to say.
 * Only for showing: the processes check the country themselves.
 */
export async function clientRegion(): Promise<ClientRegion> {
  try {
    const response = await fetch('/api/client/region', { cache: 'no-store', credentials: 'omit' })
    if (!response.ok) return UNKNOWN
    return (await response.json()) as ClientRegion
  } catch {
    return UNKNOWN
  }
}
