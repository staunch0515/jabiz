import { act, cleanup, render, screen } from '@testing-library/react'
import { i18n, setEnvironment, forgetEnvironment } from '@jabiz/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { EnvironmentBanner } from './environment-banner'

describe('EnvironmentBanner (phase 16zd)', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    document.title = 'Carriers'
    setEnvironment('staging')
  })
  afterEach(() => {
    cleanup()
    forgetEnvironment()
  })

  it('names a test system, loudly, and in the title', async () => {
    const { container } = render(<EnvironmentBanner />)
    const banner = screen.getByRole('note')
    expect(banner).toHaveAttribute('data-environment', 'staging')
    expect(banner).toHaveTextContent('staging')
    expect(banner).toHaveTextContent('Test system, not the production system')
    expect(document.title).toBe('[STAGING] Carriers')
    await expectAccessible(container)
  })

  it('follows the page title and takes its prefix away when it goes', () => {
    const { unmount } = render(<EnvironmentBanner />)
    expect(document.title).toBe('[STAGING] Carriers')
    unmount()
    expect(document.title).toBe('Carriers')
  })

  it('shows nothing on production, or while the environment is not known', () => {
    setEnvironment('production')
    const { rerender } = render(<EnvironmentBanner />)
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    expect(document.title).toBe('Carriers')
    act(() => forgetEnvironment())
    rerender(<EnvironmentBanner />)
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })

  it('takes an environment given to it, in every language', async () => {
    render(<EnvironmentBanner environment={{ name: 'test', production: false }} />)
    expect(screen.getByRole('note')).toHaveTextContent('test')
    await act(() => i18n.changeLanguage('zh'))
    expect(screen.getByRole('note')).toHaveTextContent('测试环境，不是正式版')
    await act(() => i18n.changeLanguage('ja'))
    expect(screen.getByRole('note')).toHaveTextContent('テスト環境、本番ではありません')
  })
})
