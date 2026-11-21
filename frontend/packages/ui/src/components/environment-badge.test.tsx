import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { configureClient, i18n, setEnvironment, forgetEnvironment } from '@jabiz/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { EnvironmentBadge } from './environment-badge'
import { EnvironmentBanner } from './environment-banner'

describe('EnvironmentBadge (phases 16zd and 17zz27)', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    document.title = 'Carriers'
    window.sessionStorage.clear()
    setEnvironment('staging')
  })
  afterEach(() => {
    cleanup()
    forgetEnvironment()
    window.sessionStorage.clear()
  })

  it('names a test system in a small corner badge that takes no room, and in the title', async () => {
    const { container } = render(<EnvironmentBadge />)
    const badge = screen.getByRole('note')
    expect(badge).toHaveAttribute('data-environment', 'staging')
    expect(badge).toHaveTextContent('staging')
    // Said to a screen reader and on hover; not written across the page.
    expect(badge).toHaveTextContent('Test system, not the production system')
    expect(badge).toHaveAttribute('title', 'Test system, not the production system')
    // Fixed at the bottom left, over the page and under the dialogs: no line of the page's own.
    expect(badge).toHaveClass('fixed', 'left-3', 'z-40')
    expect(badge.className).not.toContain('right-')
    expect(badge).toHaveAttribute('data-collapsed', 'false')
    expect(document.title).toBe('[STAGING] Carriers')
    await expectAccessible(container)
  })

  it('folds into a dot, opens again, and keeps the choice for the tab', async () => {
    const user = userEvent.setup()
    const { container, unmount } = render(<EnvironmentBadge />)
    await user.click(screen.getByTestId('environment-badge-collapse'))
    expect(screen.getByRole('note')).toHaveAttribute('data-collapsed', 'true')
    expect(screen.queryByTestId('environment-badge-collapse')).toBeNull()
    const dot = screen.getByTestId('environment-badge-expand')
    expect(dot).toHaveAccessibleName('Show the test system mark: staging')
    // The title keeps saying it.
    expect(document.title).toBe('[STAGING] Carriers')
    await expectAccessible(container)
    expect(window.sessionStorage.getItem('jabiz.environment-badge.collapsed')).toBe('1')

    // Another page of the same tab starts folded.
    unmount()
    render(<EnvironmentBadge />)
    expect(screen.getByRole('note')).toHaveAttribute('data-collapsed', 'true')
    await user.click(screen.getByTestId('environment-badge-expand'))
    expect(screen.getByRole('note')).toHaveAttribute('data-collapsed', 'false')
    expect(window.sessionStorage.getItem('jabiz.environment-badge.collapsed')).toBeNull()
  })

  it('works without storage: shown, and folding still works for the page', async () => {
    const user = userEvent.setup()
    const blocked = () => { throw new DOMException('blocked', 'SecurityError') }
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked)
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked)
    try {
      render(<EnvironmentBadge />)
      expect(screen.getByRole('note')).toHaveAttribute('data-collapsed', 'false')
      await user.click(screen.getByTestId('environment-badge-collapse'))
      expect(screen.getByRole('note')).toHaveAttribute('data-collapsed', 'true')
    } finally {
      get.mockRestore()
      set.mockRestore()
    }
  })

  it('follows the page title and takes its prefix away when it goes', () => {
    const { unmount } = render(<EnvironmentBadge />)
    expect(document.title).toBe('[STAGING] Carriers')
    unmount()
    expect(document.title).toBe('Carriers')
  })

  it('shows nothing on production, or while the environment is not known', () => {
    setEnvironment('production')
    const { rerender } = render(<EnvironmentBadge />)
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    expect(document.title).toBe('Carriers')
    act(() => forgetEnvironment())
    rerender(<EnvironmentBadge />)
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })

  it('takes an environment given to it, in every language', async () => {
    render(<EnvironmentBadge environment={{ name: 'test', production: false }} />)
    expect(screen.getByRole('note')).toHaveTextContent('test')
    await act(() => i18n.changeLanguage('zh'))
    expect(screen.getByRole('note')).toHaveTextContent('测试环境，不是正式版')
    expect(screen.getByTestId('environment-badge-collapse')).toHaveAccessibleName('收起测试环境标志')
    await act(() => i18n.changeLanguage('ja'))
    expect(screen.getByRole('note')).toHaveTextContent('テスト環境、本番ではありません')
    expect(screen.getByTestId('environment-badge-collapse')).toHaveAccessibleName('テスト環境の表示をたたむ')
  })

  it('follows the application\'s override, title included (phase 17ze)', () => {
    try {
      configureClient({ environment: (server, location) => (location.hostname === 'localhost'
        ? { name: 'production', production: true } : server) })
      render(<EnvironmentBadge />)
      expect(screen.queryByRole('note')).toBeNull()
      expect(document.title).toBe('Carriers')
    } finally {
      configureClient({ environment: null })
    }
  })

  it('is still exported under its old name, EnvironmentBanner', () => {
    expect(EnvironmentBanner).toBe(EnvironmentBadge)
  })
})
