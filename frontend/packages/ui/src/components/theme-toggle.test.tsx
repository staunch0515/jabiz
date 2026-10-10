import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import { AppearanceProvider, storedAppearance } from './appearance'
import { ThemeToggle } from './theme-toggle'

function renderToggle() {
  return render(
    <AppearanceProvider>
      <ThemeToggle />
    </AppearanceProvider>,
  )
}

const html = () => document.documentElement

describe('ThemeToggle', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    window.localStorage.clear()
    html().classList.remove('dark')
  })
  afterEach(() => {
    vi.restoreAllMocks()
    html().classList.remove('dark')
  })

  it('offers light, dark and the system appearance, and remembers the choice', async () => {
    renderToggle()
    const button = screen.getByRole('button', { name: 'Appearance: System' })
    await userEvent.click(button)
    const items = screen.getAllByRole('menuitemradio')
    expect(items.map((item) => item.textContent)).toEqual(['Light', 'Dark', 'System'])
    expect(screen.getByRole('menuitemradio', { name: 'System' })).toHaveAttribute('aria-checked', 'true')
    await expectAccessible()

    await userEvent.click(screen.getByRole('menuitemradio', { name: 'Dark' }))
    expect(html()).toHaveClass('dark')
    expect(window.localStorage.getItem('jabiz.appearance')).toBe('dark')
    expect(screen.getByRole('button', { name: 'Appearance: Dark' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Appearance: Dark' }))
    await userEvent.click(screen.getByRole('menuitemradio', { name: 'Light' }))
    expect(html()).not.toHaveClass('dark')
    expect(window.localStorage.getItem('jabiz.appearance')).toBe('light')

    await userEvent.click(screen.getByRole('button', { name: 'Appearance: Light' }))
    await userEvent.click(screen.getByRole('menuitemradio', { name: 'System' }))
    expect(window.localStorage.getItem('jabiz.appearance')).toBeNull()
  })

  it('opens from the keyboard', async () => {
    renderToggle()
    screen.getByRole('button', { name: /Appearance/ }).focus()
    await userEvent.keyboard('{Enter}')
    expect(screen.getByRole('menu')).toBeInTheDocument()
    // Focus starts on the first item (Light); one down is Dark.
    await userEvent.keyboard('{ArrowDown}{Enter}')
    expect(window.localStorage.getItem('jabiz.appearance')).toBe('dark')
  })

  it('starts from the remembered choice', () => {
    window.localStorage.setItem('jabiz.appearance', 'dark')
    renderToggle()
    expect(html()).toHaveClass('dark')
    expect(screen.getByRole('button', { name: 'Appearance: Dark' })).toBeInTheDocument()
  })

  it('follows the system when storage is not available, and still switches for the page', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    expect(storedAppearance()).toBe('system')
    renderToggle()
    await userEvent.click(screen.getByRole('button', { name: 'Appearance: System' }))
    await userEvent.click(screen.getByRole('menuitemradio', { name: 'Dark' }))
    expect(html()).toHaveClass('dark')
  })

  it('ignores a stored value it does not know', () => {
    window.localStorage.setItem('jabiz.appearance', 'sepia')
    expect(storedAppearance()).toBe('system')
  })

  it('is named in the interface language', async () => {
    await act(() => i18n.changeLanguage('zh'))
    renderToggle()
    expect(screen.getByRole('button', { name: '外观: 跟随系统' })).toBeInTheDocument()
  })
})
