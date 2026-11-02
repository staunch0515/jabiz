import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { configureSession } from '../api/session'
import { json, urlOf } from '../test/locks'
import { forgetEntryInfo } from './account'
import { configureClient } from '../configure'
import { forgetEnvironment, setEnvironment, toEnvironment, useEnvironment } from './environment'

function server(environment: string | undefined, status = 200) {
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(urlOf(input), 'http://localhost').pathname
    calls.push(path)
    if (path.startsWith('/api/auth/entries/')) {
      return status === 200 ? json(200, { name: 'admin', sessionPersistence: 'tab', environment }) : json(status, {})
    }
    return json(404, {})
  }))
  return calls
}

describe('useEnvironment (phase 16zd)', () => {
  beforeEach(() => {
    configureSession('admin')
    forgetEntryInfo()
    forgetEnvironment()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads the environment from the entry once and tells whether it is production', async () => {
    const calls = server('staging')
    const first = renderHook(() => useEnvironment())
    expect(first.result.current).toBeUndefined()
    await waitFor(() => expect(first.result.current).toEqual({ name: 'staging', production: false }))
    const second = renderHook(() => useEnvironment())
    expect(second.result.current).toEqual({ name: 'staging', production: false })
    expect(calls.filter((path) => path.startsWith('/api/auth/entries/'))).toHaveLength(1)
  })

  it('knows production whatever its case, and takes a missing name for a test system', () => {
    expect(toEnvironment('Production')).toEqual({ name: 'Production', production: true })
    expect(toEnvironment(undefined)).toEqual({ name: 'test', production: false })
    expect(toEnvironment('  ')).toEqual({ name: 'test', production: false })
  })

  it('stays unknown while the server cannot be reached, and asks again after a pause', async () => {
    vi.useFakeTimers()
    try {
      const calls = server(undefined, 503)
      const { result } = renderHook(() => useEnvironment())
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(result.current).toBeUndefined()
      expect(calls.filter((path) => path.startsWith('/api/auth/entries/'))).toHaveLength(1)
      // The server is back: the next ask, five seconds later, finds it.
      server('staging')
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5_000)
      })
      expect(result.current).toEqual({ name: 'staging', production: false })
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops asking once nobody shows it', async () => {
    vi.useFakeTimers()
    try {
      const calls = server(undefined, 503)
      const { unmount } = renderHook(() => useEnvironment())
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      unmount()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(60_000)
      })
      expect(calls.filter((path) => path.startsWith('/api/auth/entries/'))).toHaveLength(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('can be set and forgotten without the server', () => {
    const { result } = renderHook(() => useEnvironment())
    act(() => setEnvironment('uat'))
    expect(result.current).toEqual({ name: 'uat', production: false })
    act(() => forgetEnvironment())
    expect(result.current).toBeUndefined()
  })
})

describe('the application\'s override (phase 17ze)', () => {
  beforeEach(() => {
    configureSession('admin')
    forgetEntryInfo()
    forgetEnvironment()
  })
  afterEach(() => {
    configureClient({ environment: null })
    vi.unstubAllGlobals()
  })

  it('shows what the override chooses for this page, given the server\'s word and the host', async () => {
    server('staging')
    const seen: { name: string; hostname: string }[] = []
    configureClient({ environment: (server, location) => {
      seen.push({ name: server.name, hostname: location.hostname })
      return { name: 'production', production: true }
    } })
    const { result } = renderHook(() => useEnvironment())
    await waitFor(() => expect(result.current).toEqual({ name: 'production', production: true }))
    expect(seen).toContainEqual({ name: 'staging', hostname: 'localhost' })
  })

  it('keeps the server\'s word when the override returns nothing or throws', async () => {
    server('staging')
    configureClient({ environment: () => undefined })
    const kept = renderHook(() => useEnvironment())
    await waitFor(() => expect(kept.result.current).toEqual({ name: 'staging', production: false }))
    configureClient({ environment: () => {
      throw new Error('broken')
    } })
    expect(kept.result.current).toEqual({ name: 'staging', production: false })
  })

  it('applies at once when set or removed, without asking the server again', async () => {
    const calls = server('staging')
    const { result } = renderHook(() => useEnvironment())
    await waitFor(() => expect(result.current).toEqual({ name: 'staging', production: false }))
    act(() => configureClient({ environment: () => ({ name: 'production', production: true }) }))
    expect(result.current).toEqual({ name: 'production', production: true })
    act(() => configureClient({ environment: null }))
    expect(result.current).toEqual({ name: 'staging', production: false })
    // Configuring something else leaves the override as it is.
    act(() => configureClient({ environment: () => ({ name: 'demo', production: false }) }))
    act(() => configureClient({ nativeShell: null }))
    expect(result.current).toEqual({ name: 'demo', production: false })
    expect(calls.filter((path) => path.startsWith('/api/auth/entries/'))).toHaveLength(1)
  })

  it('is applied to an environment set without the server too', () => {
    configureClient({ environment: (server) => (server.name === 'staging' ? { name: 'production', production: true }
      : undefined) })
    setEnvironment('staging')
    const { result } = renderHook(() => useEnvironment())
    expect(result.current).toEqual({ name: 'production', production: true })
    act(() => setEnvironment('test'))
    expect(result.current).toEqual({ name: 'test', production: false })
  })
})
