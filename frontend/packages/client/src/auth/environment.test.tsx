import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { configureSession } from '../api/session'
import { json, urlOf } from '../test/locks'
import { forgetEntryInfo } from './account'
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
