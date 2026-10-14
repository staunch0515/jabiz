import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { currentPath, hrefOf, Link, navigate } from './router'

beforeEach(() => window.history.replaceState(null, '', '/'))

describe('the router', () => {
  it('maps the SPA\'s paths onto the build\'s base (the root in tests)', () => {
    expect(hrefOf('/account')).toBe('/account')
    expect(currentPath('/account')).toBe('/account')
    navigate('/account')
    expect(window.location.pathname).toBe('/account')
  })

  it('never leaves the site: another host goes to the SPA\'s home', () => {
    navigate('//evil.example/x')
    expect(window.location.host).toBe(new URL(document.baseURI).host)
    expect(window.location.pathname).toBe('/')
    navigate('https://evil.example/x')
    expect(window.location.host).toBe(new URL(document.baseURI).host)
  })

  it('follows a link without reloading and marks the current page', async () => {
    render(<Link to="/account" current>Account</Link>)
    const link = screen.getByRole('link', { name: 'Account' })
    expect(link).toHaveAttribute('href', '/account')
    expect(link).toHaveAttribute('aria-current', 'page')
    await userEvent.click(link)
    expect(window.location.pathname).toBe('/account')
  })
})
