import { configureClient, i18n, session } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { Label } from '../components/ui/label'
import { authMessages, ChangePasswordForm, PasswordInput, SignInForm, SignUpForm } from './index'

const user = () => userEvent.setup()
const SHOW = 'Show typed characters'

beforeEach(async () => {
  configureClient({ entry: 'portal' })
  // The forms ask the entry what it offers; nothing else is sent before submitting.
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname
    const body = path.startsWith('/api/auth/entries/') ? { name: 'portal', selfRegistration: true } : []
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
  vi.unstubAllGlobals()
})

describe('PasswordInput (phase 17p)', () => {
  it('shows what was typed and hides it again, as a named toggle for the field', async () => {
    const ref = createRef<HTMLInputElement>()
    render(
      <>
        <Label htmlFor="pw">Current password</Label>
        <PasswordInput id="pw" ref={ref} autoComplete="current-password" />
      </>,
    )
    const field = screen.getByLabelText('Current password')
    expect(ref.current).toBe(field)
    expect(field).toHaveAttribute('type', 'password')
    expect(field).toHaveAttribute('autocomplete', 'current-password')
    const toggle = screen.getByRole('button', { name: SHOW })
    expect(toggle).toHaveAttribute('type', 'button')
    expect(toggle).toHaveAttribute('aria-controls', 'pw')
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await expectAccessible()

    await user().type(field, 'secret')
    await user().click(toggle)
    expect(field).toHaveAttribute('type', 'text')
    expect(field).toHaveValue('secret')
    // One name in both states (a toggle button's state is its pressed state); the icon changes.
    expect(screen.getByRole('button', { name: SHOW, pressed: true })).toBe(toggle)
    await expectAccessible()

    await user().click(toggle)
    expect(field).toHaveAttribute('type', 'password')
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
  })

  it('is reached from the keyboard right after its field', async () => {
    render(<PasswordInput aria-label="Password" />)
    await user().tab()
    expect(screen.getByLabelText('Password')).toHaveFocus()
    await user().tab()
    expect(screen.getByRole('button', { name: SHOW })).toHaveFocus()
    await user().keyboard(' ')
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text')
    await user().keyboard('{Enter}')
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
  })

  it('is disabled with its field', () => {
    render(<PasswordInput aria-label="Password" disabled />)
    expect(screen.getByRole('button', { name: SHOW })).toBeDisabled()
  })

  it('is named in every platform language without the word for password, so a field found by name stays unique', () => {
    expect(authMessages.en.common.showTyped).toBe(SHOW)
    expect(authMessages.zh.common.showTyped).toBe('显示输入的字符')
    expect(authMessages.ja.common.showTyped).toBe('入力した文字を表示')
    expect(authMessages.en.common.showTyped.toLowerCase()).not.toContain('password')
    expect(authMessages.zh.common.showTyped).not.toContain('密码')
    expect(authMessages.ja.common.showTyped).not.toContain('パスワード')
  })
})

describe('the password fields of the blocks (phase 17p)', () => {
  it('sign in: the password can be shown, is found alone by its name, and is hidden again when the form is sent', async () => {
    const signIn = vi.fn().mockResolvedValue({ status: 'SIGNED_IN' })
    const onSignedIn = vi.fn()
    render(<SignInForm brand="Portal" returnTo="/portal/" auth={{ signIn, verify: vi.fn() }} providers={[]} onSignedIn={onSignedIn} />)
    await user().type(screen.getByRole('textbox', { name: 'User name' }), 'ann')
    expect(screen.getAllByLabelText(/password/i)).toEqual([screen.getByLabelText('Password')])
    const field = screen.getByLabelText('Password')
    await user().type(field, 'secret')
    await user().click(screen.getByRole('button', { name: SHOW }))
    expect(field).toHaveAttribute('type', 'text')
    await expectAccessible()

    await user().type(field, '{Enter}')
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/portal/'))
    expect(signIn).toHaveBeenCalledWith('ann', 'secret')
    expect(field).toHaveAttribute('type', 'password')
    expect(screen.getByRole('button', { name: SHOW })).toHaveAttribute('aria-pressed', 'false')
  })

  it('sign up: the new password and its repetition are shown each on its own', async () => {
    render(<SignUpForm brand="Portal" />)
    const password = screen.getByLabelText('New password')
    const confirm = screen.getByLabelText('Repeat the new password')
    const toggles = screen.getAllByRole('button', { name: SHOW })
    expect(toggles).toHaveLength(2)
    expect(toggles[0]).toHaveAttribute('aria-controls', password.id)
    expect(toggles[1]).toHaveAttribute('aria-controls', confirm.id)
    await user().click(toggles[1])
    expect(password).toHaveAttribute('type', 'password')
    expect(confirm).toHaveAttribute('type', 'text')
    expect(confirm).toHaveAttribute('autocomplete', 'new-password')
    await expectAccessible()
  })

  it('changing the password: every one of its three fields can be shown', async () => {
    render(<ChangePasswordForm change={vi.fn()} />)
    const form = screen.getByRole('button', { name: 'Change the password' }).closest('form')!
    const toggles = within(form).getAllByRole('button', { name: SHOW })
    expect(toggles).toHaveLength(3)
    for (const toggle of toggles) await user().click(toggle)
    for (const name of ['Current password', 'New password', 'Repeat the new password']) {
      expect(within(form).getByLabelText(name)).toHaveAttribute('type', 'text')
    }
  })
})
