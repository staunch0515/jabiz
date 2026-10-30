import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  isProxyUnavailable,
  noteNetworkFailure,
  noteResponse,
  observedFetch,
  onServiceRestored,
  PROBE_MAX_MS,
  PROBE_MIN_MS,
  probeService,
  resetServiceAvailability,
  serviceUnavailable,
  useServiceUnavailable,
} from './availability'
import { api, sessionFetch } from './client'

/** The reverse proxy's maintenance page: HTML, 503, `Retry-After`. */
function maintenance(retryAfter = '300') {
  return new Response('<!doctype html><title>Maintenance</title>', { status: 503,
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Retry-After': retryAfter } })
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/problem+json' } })
}

beforeEach(() => {
  vi.useFakeTimers()
  resetServiceAvailability()
})
afterEach(() => {
  resetServiceAvailability()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('what counts as the service not answering (17x)', () => {
  it('is the proxy\'s gateway answer without JSON, never the application\'s own problem', () => {
    expect(isProxyUnavailable(maintenance())).toBe(true)
    expect(isProxyUnavailable(new Response('', { status: 502 }))).toBe(true)
    expect(isProxyUnavailable(new Response('', { status: 504, headers: { 'Content-Type': 'text/plain' } }))).toBe(true)
    // PROCESS_BUSY and QUERY_TIMEOUT are the application answering: a JSON problem.
    expect(isProxyUnavailable(json(503, { violations: [{ ruleCode: 'PROCESS_BUSY' }] }))).toBe(false)
    expect(isProxyUnavailable(new Response('', { status: 500 }))).toBe(false)
    expect(isProxyUnavailable(json(200, {}))).toBe(false)
  })
})

describe('the service\'s state (17x)', () => {
  it('turns unavailable on the maintenance page, probes after Retry-After within bounds, and is back on an answer', async () => {
    const restored = vi.fn()
    const off = onServiceRestored(restored)
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(maintenance('20'))
      .mockResolvedValueOnce(json(200, { name: 'admin' }))
    noteResponse(maintenance('20'))
    const first = serviceUnavailable()!
    expect(first.nextProbe - first.since).toBe(20_000)
    // A second unanswered call keeps when it started.
    noteResponse(maintenance('20'))
    expect(serviceUnavailable()!.since).toBe(first.since)

    // The probe at 20 s finds the proxy still in front: the next one is scheduled.
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(String(fetch.mock.calls[0][0])).toBe('/api/auth/entries/admin')
    expect(serviceUnavailable()).not.toBeNull()
    expect(restored).not.toHaveBeenCalled()
    // The next answers: back, and the handlers run once.
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    expect(serviceUnavailable()).toBeNull()
    expect(restored).toHaveBeenCalledTimes(1)
    off()
  })

  it('keeps the probe\'s interval between its bounds', () => {
    noteResponse(maintenance('1'))
    let state = serviceUnavailable()!
    expect(state.nextProbe - Date.now()).toBe(PROBE_MIN_MS)
    resetServiceAvailability()
    noteResponse(maintenance('86400'))
    state = serviceUnavailable()!
    expect(state.nextProbe - Date.now()).toBe(PROBE_MAX_MS)
  })

  it('takes a failed connection as unavailable while online, not an aborted call nor an offline browser', () => {
    noteNetworkFailure(new DOMException('aborted', 'AbortError'))
    expect(serviceUnavailable()).toBeNull()
    const onLine = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    noteNetworkFailure(new TypeError('Failed to fetch'))
    expect(serviceUnavailable()).toBeNull()
    onLine.mockReturnValue(true)
    noteNetworkFailure(new TypeError('Failed to fetch'))
    expect(serviceUnavailable()!.nextProbe - Date.now()).toBe(PROBE_MIN_MS)
  })

  it('is back as soon as any call is answered, and a probe asked for now shares one request', async () => {
    const restored = vi.fn()
    const off = onServiceRestored(restored)
    noteResponse(maintenance())
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(200, {}))
    await Promise.all([probeService(), probeService()])
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(serviceUnavailable()).toBeNull()
    noteResponse(maintenance())
    noteResponse(json(422, { violations: [] }))
    expect(serviceUnavailable()).toBeNull()
    expect(restored).toHaveBeenCalledTimes(2)
    off()
  })

  it('notes the calls of the typed client and of sessionFetch, and returns or throws what fetch did', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(maintenance())
    const answer = await api.GET('/api/meta/datasets')
    expect(answer.response.status).toBe(503)
    expect(serviceUnavailable()).not.toBeNull()
    resetServiceAvailability()

    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(sessionFetch('/api/files')).rejects.toThrow('Failed to fetch')
    expect(serviceUnavailable()).not.toBeNull()
    resetServiceAvailability()

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(json(503, { violations: [{ ruleCode: 'PROCESS_BUSY' }] }))
    expect((await observedFetch('/api/processes/X/1')).status).toBe(503)
    expect(serviceUnavailable()).toBeNull()
  })

  it('reads again what the page reads when the service is back', async () => {
    const client = new QueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries').mockResolvedValue()
    const wrapper = ({ children }: { children: ReactNode }) =>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    const { result } = renderHook(() => useServiceUnavailable(), { wrapper })
    expect(result.current).toBeNull()
    act(() => noteResponse(maintenance()))
    expect(result.current).not.toBeNull()
    expect(invalidate).not.toHaveBeenCalled()
    act(() => noteResponse(json(200, {})))
    expect(result.current).toBeNull()
    expect(invalidate).toHaveBeenCalledTimes(1)
  })
})
