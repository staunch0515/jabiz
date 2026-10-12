import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fakeLocks, withLocks } from '../test/locks'
import { configureSession, session } from './session'

describe('session (decision D43 item 1 and 2)', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    window.localStorage.clear()
    withLocks(fakeLocks())
  })

  afterEach(() => {
    configureSession('admin')
    session.setPersistence('tab')
    session.clear()
    window.sessionStorage.clear()
    window.localStorage.clear()
    withLocks(undefined)
    vi.restoreAllMocks()
  })

  it('keeps the refresh token under a key of the entry, apart from other entries', () => {
    configureSession('portal')
    session.store('access-p', 'refresh-p')
    expect(window.sessionStorage.getItem('jabiz.session.portal.refresh')).toBe('refresh-p')
    expect(session.entry()).toBe('portal')

    configureSession('admin')
    // The access token was the portal's: never used for another entry.
    expect(session.accessToken()).toBeNull()
    expect(session.refreshToken()).toBeNull()
    session.store('access-a', 'refresh-a')
    expect(window.sessionStorage.getItem('jabiz.session.admin.refresh')).toBe('refresh-a')
    expect(window.sessionStorage.getItem('jabiz.session.portal.refresh')).toBe('refresh-p')

    session.clear()
    expect(window.sessionStorage.getItem('jabiz.session.portal.refresh')).toBe('refresh-p')
  })

  it('refuses names that are no entry names', () => {
    expect(() => configureSession('../x')).toThrow()
    expect(() => configureSession('Portal')).toThrow()
  })

  it('keeps the token in the tab by default, and on the device when the entry says so', () => {
    configureSession('quiz')
    expect(session.persistence()).toBe('tab')
    session.store('a1', 'r1')
    expect(window.sessionStorage.getItem('jabiz.session.quiz.refresh')).toBe('r1')
    expect(window.localStorage.getItem('jabiz.session.quiz.refresh')).toBeNull()

    session.setPersistence('device')
    expect(session.persistence()).toBe('device')
    expect(window.localStorage.getItem('jabiz.session.quiz.refresh')).toBe('r1')
    expect(window.sessionStorage.getItem('jabiz.session.quiz.refresh')).toBeNull()
    session.store('a2', 'r2')
    expect(window.localStorage.getItem('jabiz.session.quiz.refresh')).toBe('r2')
    expect(session.refreshToken()).toBe('r2')

    session.setPersistence('tab')
    expect(window.localStorage.getItem('jabiz.session.quiz.refresh')).toBeNull()
    expect(window.sessionStorage.getItem('jabiz.session.quiz.refresh')).toBe('r2')
  })

  it('takes up a session kept on the device when the page opens again', () => {
    window.localStorage.setItem('jabiz.session.quiz.refresh', 'kept')
    configureSession('quiz')
    expect(session.persistence()).toBe('device')
    expect(session.refreshToken()).toBe('kept')
  })

  it('never keeps the administration on the device, nor any entry without Web Locks', () => {
    configureSession('admin')
    session.setPersistence('device')
    expect(session.persistence()).toBe('tab')

    // A token found on the device for the admin continues in this tab only.
    window.localStorage.setItem('jabiz.session.admin.refresh', 'planted')
    configureSession('portal')
    configureSession('admin')
    expect(window.localStorage.getItem('jabiz.session.admin.refresh')).toBeNull()
    expect(session.refreshToken()).toBe('planted')

    withLocks(undefined)
    configureSession('quiz')
    session.setPersistence('device')
    expect(session.persistence()).toBe('tab')
  })

  it("moves the admin's token from the key of before, once", () => {
    window.sessionStorage.setItem('jabiz.refreshToken', 'legacy')
    configureSession('portal')
    // Only the administration's: the key of before was the admin's session.
    expect(session.refreshToken()).toBeNull()
    configureSession('admin')
    expect(session.refreshToken()).toBe('legacy')
    expect(window.sessionStorage.getItem('jabiz.refreshToken')).toBeNull()
    expect(window.sessionStorage.getItem('jabiz.session.admin.refresh')).toBe('legacy')
  })

  it('does not let the key of before replace a newer session', () => {
    window.sessionStorage.setItem('jabiz.session.admin.refresh', 'current')
    window.sessionStorage.setItem('jabiz.refreshToken', 'legacy')
    configureSession('admin')
    expect(session.refreshToken()).toBe('current')
    expect(window.sessionStorage.getItem('jabiz.refreshToken')).toBeNull()
  })

  it('signs this tab out when another tab of a session kept on the device signs out', () => {
    configureSession('quiz')
    session.setPersistence('device')
    session.store('access', 'r1')
    const listener = vi.fn()
    const stop = session.subscribe(listener)
    window.dispatchEvent(new StorageEvent('storage', { key: 'jabiz.session.quiz.refresh', oldValue: 'r1', newValue: 'r2' }))
    expect(session.accessToken()).toBe('access')
    window.dispatchEvent(new StorageEvent('storage', { key: 'jabiz.session.quiz.refresh', oldValue: 'r2', newValue: null }))
    expect(session.accessToken()).toBeNull()
    expect(listener).toHaveBeenCalled()
    stop()
  })
})
