import { i18n, type InstallBrowser, type InstallPrompt, type InstallSituation } from '@jabiz/client'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { PwaInstallBanner } from './index'

const DAY = 86_400_000

function prompt(situation: InstallSituation, install = vi.fn(async () => 'accepted' as const),
  browser: InstallBrowser = 'other'): InstallPrompt {
  return { situation, browser, install }
}

beforeEach(async () => {
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  window.localStorage.clear()
})

describe('PwaInstallBanner', () => {
  it('offers the browser\'s own installation where it has one', async () => {
    const offered = prompt('prompt')
    render(<PwaInstallBanner appName="QuizBuks" prompt={offered} rememberAs="t1" />)
    const banner = screen.getByRole('region', { name: 'Add QuizBuks to your home screen' })
    expect(banner).toHaveTextContent('Open it in one tap')
    await expectAccessible()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Install' }))
    expect(offered.install).toHaveBeenCalledOnce()
  })

  it('guides iOS through the Share menu, saying that the home-screen app signs in again', async () => {
    render(<PwaInstallBanner appName="QuizBuks" prompt={prompt('ios')} rememberAs="t2" />)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Show me how' }))
    const guide = await screen.findByTestId('pwa-install-guide')
    expect(guide).toHaveTextContent('Tap the Share button')
    expect(guide).toHaveTextContent('Add to Home Screen')
    expect(guide).toHaveTextContent('behind "…"')
    expect(screen.getByTestId('pwa-install-sign-in')).toHaveTextContent('sign in once more')
    await expectAccessible()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByTestId('pwa-install-guide')).toBeNull()
  })

  it('sends another app\'s browser to a real one, and Firefox to its menu', () => {
    const { rerender } = render(<PwaInstallBanner appName="QuizBuks" prompt={prompt('in-app')} rememberAs="t3" />)
    expect(screen.getByTestId('pwa-install')).toHaveTextContent('open this page in Safari or Chrome')
    expect(screen.queryByRole('button', { name: 'Install' })).toBeNull()
    rerender(<PwaInstallBanner appName="QuizBuks" prompt={prompt('menu')} rememberAs="t3" />)
    expect(screen.getByTestId('pwa-install')).toHaveTextContent('in your browser\'s menu')
  })

  it('guides an Android browser through its own menu, with the permission some phones ask for (phase 17m)', async () => {
    const steps = async (browser: InstallBrowser) => {
      const { unmount } = render(<PwaInstallBanner appName="QuizBuks" rememberAs={`m-${browser}`}
        prompt={prompt('menu', undefined, browser)} />)
      await userEvent.setup().click(screen.getByRole('button', { name: 'Show me how' }))
      const guide = await screen.findByTestId('pwa-install-guide')
      const text = guide.textContent ?? ''
      const permission = screen.queryByTestId('pwa-install-permission') !== null
      expect(screen.queryByTestId('pwa-install-sign-in')).toBeNull()
      await expectAccessible()
      unmount()
      return { text, permission }
    }
    const huawei = await steps('huawei')
    expect(huawei.text).toContain('"≡" at the bottom')
    expect(huawei.text).toContain('"Add to" first')
    expect(huawei.permission).toBe(true)
    const uc = await steps('uc')
    expect(uc.text).toContain('under "Toolbox"')
    const firefox = await steps('firefox')
    expect(firefox.text).toContain('next to the address bar')
    expect(firefox.permission).toBe(false)
    const other = await steps('other')
    expect(other.text).toContain('usually "⋮", "≡" or "…"')
    expect(other.permission).toBe(true)
  })

  it('tells WeChat and QQ users where their "open in the browser" is, as a warning, in every language', async () => {
    const { rerender } = render(<PwaInstallBanner appName="QuizBuks" rememberAs="w1"
      prompt={prompt('in-app', undefined, 'wechat')} />)
    const banner = screen.getByTestId('pwa-install')
    expect(banner).toHaveTextContent('tap "···" at the top right')
    expect(banner).toHaveClass('border-warning')
    await expectAccessible()
    await act(() => i18n.changeLanguage('zh'))
    rerender(<PwaInstallBanner appName="QuizBuks" rememberAs="w1" prompt={prompt('in-app', undefined, 'qq-app')} />)
    expect(screen.getByTestId('pwa-install')).toHaveTextContent('点右上角的"···"，再选"在浏览器打开"')
    // Other apps: their own menu; no browser named that most phones in China do not have.
    rerender(<PwaInstallBanner appName="QuizBuks" rememberAs="w1" prompt={prompt('in-app')} />)
    expect(screen.getByTestId('pwa-install')).toHaveTextContent('用手机的浏览器打开本页')
    expect(screen.getByTestId('pwa-install')).not.toHaveTextContent('Chrome')
    await act(() => i18n.changeLanguage('ja'))
    rerender(<PwaInstallBanner appName="QuizBuks" rememberAs="w1" prompt={prompt('in-app', undefined, 'wechat')} />)
    expect(screen.getByTestId('pwa-install')).toHaveTextContent('右上の「···」')
  })

  it('offers nothing on a computer or once installed', () => {
    const { rerender } = render(<PwaInstallBanner appName="QuizBuks" prompt={prompt('none')} />)
    expect(screen.queryByTestId('pwa-install')).toBeNull()
    rerender(<PwaInstallBanner appName="QuizBuks" prompt={prompt('installed')} />)
    expect(screen.queryByTestId('pwa-install')).toBeNull()
  })

  it('stays away for 30 days after a close, and for good after three', async () => {
    let now = 0
    const clock = () => now
    const show = () => render(<PwaInstallBanner appName="QuizBuks" prompt={prompt('ios')} rememberAs="t4" now={clock} />)
    for (let close = 1; close <= 3; close++) {
      const { unmount } = show()
      await userEvent.setup().click(screen.getByRole('button', { name: 'Not now' }))
      expect(screen.queryByTestId('pwa-install')).toBeNull()
      unmount()
      now += 29 * DAY
      show().unmount()
      expect(screen.queryByTestId('pwa-install')).toBeNull()
      now += DAY
    }
    show()
    expect(screen.queryByTestId('pwa-install')).toBeNull()
  })

  it('stays on the page that is the place to install, without closing, and says when it is installed', () => {
    rememberAs3Times('t5')
    const { rerender } = render(<PwaInstallBanner appName="QuizBuks" prompt={prompt('ios')} rememberAs="t5"
      dismissible={false} />)
    expect(screen.getByTestId('pwa-install')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Not now' })).toBeNull()
    rerender(<PwaInstallBanner appName="QuizBuks" prompt={prompt('installed')} dismissible={false} />)
    expect(screen.getByTestId('pwa-install')).toHaveTextContent('QuizBuks is on this device\'s home screen.')
  })
})

function rememberAs3Times(key: string) {
  window.localStorage.setItem(`jabiz.pwa.install.${key}`, JSON.stringify({ at: Date.now(), count: 3 }))
}
