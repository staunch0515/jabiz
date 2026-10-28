import { changeLanguage } from '@jabiz/client'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountInstallEntry, answeredOnce, HomeInstallOffer, INSTALLED, rememberInstalled, ResultInstallOffer }
  from './install'
import { rememberShell } from './shell'

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1'

// An iPhone is told by its user agent (only an iPad needs the touch screen, which jsdom does not have).
function onDevice(userAgent: string) {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(userAgent)
}

beforeEach(async () => {
  await changeLanguage('en')
})

afterEach(() => {
  vi.restoreAllMocks()
  window.localStorage.clear()
  window.sessionStorage.clear()
})

describe('telling the business site the app is installed (Q41)', () => {
  const standalone = (matches: boolean) => vi.spyOn(window, 'matchMedia').mockImplementation((query: string) =>
    ({ matches: matches && query === '(display-mode: standalone)' }) as MediaQueryList)

  it('notes it when the app runs installed', () => {
    standalone(true)
    rememberInstalled(window)
    expect(window.localStorage.getItem(INSTALLED)).toBe('true')
  })

  it('notes it when the app is installed while open, and not before', () => {
    standalone(false)
    rememberInstalled(window)
    expect(window.localStorage.getItem(INSTALLED)).toBeNull()
    window.dispatchEvent(new Event('appinstalled'))
    expect(window.localStorage.getItem(INSTALLED)).toBe('true')
  })
})

describe('offering to add the app to the home screen (Q38)', () => {
  it('waits for a finished quiz before home offers it', () => {
    onDevice(IPHONE)
    const { unmount } = render(<HomeInstallOffer />)
    expect(screen.queryByTestId('pwa-install')).toBeNull()
    unmount()
    // The first result offers it, and from then on home does too.
    render(<ResultInstallOffer />).unmount()
    expect(answeredOnce()).toBe(true)
    render(<HomeInstallOffer />)
    expect(screen.getByTestId('pwa-install')).toHaveAttribute('data-situation', 'ios')
    expect(screen.getByRole('region', { name: 'Add QuizBuks to your home screen' })).toBeTruthy()
  })

  it('keeps an entry on the account page that cannot be closed', () => {
    onDevice(IPHONE)
    window.localStorage.setItem('jabiz.pwa.install.taker', JSON.stringify({ at: Date.now(), count: 3 }))
    render(<AccountInstallEntry />)
    expect(screen.getByTestId('pwa-install')).toBeTruthy()
    expect(screen.queryByTestId('pwa-install-dismiss')).toBeNull()
  })

  it('offers nothing on a computer', () => {
    onDevice('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36')
    render(<><ResultInstallOffer /><AccountInstallEntry /></>)
    expect(screen.queryByTestId('pwa-install')).toBeNull()
  })
})

describe('in the store app (Q49b)', () => {
  it('offers nothing anywhere, the account page included', () => {
    // A device that is offered it in a browser (shell.ts knows only the Android shell so far, the check is the same).
    onDevice(IPHONE)
    window.localStorage.setItem('qb.answeredOnce', 'true')
    const { unmount } = render(<AccountInstallEntry />)
    expect(screen.getByTestId('pwa-install')).toBeTruthy()
    unmount()
    window.history.replaceState(null, '', '/taker/?shell=android')
    rememberShell(window)
    render(<><HomeInstallOffer /><ResultInstallOffer /><AccountInstallEntry /></>)
    expect(screen.queryByTestId('pwa-install')).toBeNull()
    window.history.replaceState(null, '', '/')
  })
})
