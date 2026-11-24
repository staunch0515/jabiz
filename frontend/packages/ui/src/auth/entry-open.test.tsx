import { configureClient, i18n, session } from '@jabiz/client'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { AppearanceProvider } from '../components/appearance'
import { FloatingInput } from '../components/floating-input'
import { EntryLayout, EntryLayoutProvider, EntryLink, SignInForm } from './index'

/*
 * Phase 17zz30 (12 §13.1): the open look of the entry pages (QuizBuks Q113 §9.4) — no card, the mark and the name
 * above the form, pill buttons, fields labelled inside — and every slot of the template still in place. The card look
 * is unchanged (entry.test.tsx).
 */

let entryNo = 0

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(async () => {
  entryNo += 1
  configureClient({ entry: `open${entryNo}` })
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input instanceof Request ? input.url : input), 'http://localhost').pathname
    if (path.startsWith('/api/auth/entries/')) return json(200, { name: path.split('/').pop(), appPath: '/' })
    if (path === '/api/auth/oidc/providers') return json(200, [])
    return json(500, {})
  }))
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1280 })
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
  vi.unstubAllGlobals()
})

const Mark = () => <svg data-testid="the-mark" viewBox="0 0 24 24"><rect width="24" height="24" /></svg>

describe('the open look of the entry pages', () => {
  it('draws no card: the name as the page heading, the mark large beside the column, every slot still there', async () => {
    const { container } = render(
      <AppearanceProvider>
        <EntryLayoutProvider look="open" mark={<Mark />} brand="Study Well"
          intro={{ sentence: 'Answer, learn, get rewarded' }}
          links={<EntryLink href="/help">Help</EntryLink>}
          otherEntry={{ text: 'Publishing quizzes?', href: '/sponsor/', link: 'Go to the Sponsor app' }}
          footer="build 1">
          <EntryLayout title="Reset your password" testId="reset" showOtherEntry below="A hint">
            <p>The form</p>
          </EntryLayout>
        </EntryLayoutProvider>
      </AppearanceProvider>,
    )
    const layout = container.querySelector('[data-slot="entry-layout"]')!
    expect(layout).toHaveAttribute('data-look', 'open')
    expect(container.querySelector('[data-slot="card"]')).toBeNull()
    expect(screen.getByRole('heading', { level: 1, name: 'Study Well' })).toHaveClass('lg:text-[4rem]')
    // A page's own title is shown (a step: "Reset your password"); the mark is there twice, small and large, both decorative.
    expect(screen.getByRole('heading', { level: 2, name: 'Reset your password' })).not.toHaveClass('sr-only')
    expect(screen.getAllByTestId('the-mark')).toHaveLength(2)
    expect(container.querySelector('[data-slot="entry-mark"]')).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByTestId('reset')).toHaveTextContent('The form')
    expect(screen.getByText('A hint')).toBeInTheDocument()
    expect(screen.getByText('Answer, learn, get rewarded')).toBeInTheDocument()
    expect(within(screen.getByTestId('entry-other')).getByRole('link', { name: 'Go to the Sponsor app' }))
      .toHaveAttribute('href', '/sponsor/')
    expect(within(screen.getByTestId('entry-links')).getByRole('link', { name: 'Help' })).toBeInTheDocument()
    expect(screen.getByTestId('auth-footer')).toHaveTextContent('build 1')
    await expectAccessible(container)
  })

  it('makes the first step of signing in quiet, its buttons pills and its fields labelled inside', async () => {
    const signIn = vi.fn()
    const { container } = render(
      <EntryLayoutProvider look="open" mark={<Mark />} brand="Study Well">
        <SignInForm brand="Study Well" returnTo="/" auth={{ signIn, verify: vi.fn() }} providers={[]} />
      </EntryLayoutProvider>,
    )
    // The page's title is for screen readers only on the first step, as on a timeline's sign-in.
    expect(await screen.findByRole('heading', { level: 2 })).toHaveClass('sr-only')
    const password = screen.getByLabelText('Password', { selector: 'input' })
    expect(password).toHaveAttribute('placeholder', ' ')
    expect(password).toHaveClass('h-[3.875rem]', 'rounded-[6px]')
    expect(password.closest('[data-slot="floating-input"]')).not.toBeNull()
    // The password field keeps its button that shows what was typed.
    expect(screen.getByTestId('password-reveal')).toBeInTheDocument()
    const submit = screen.getByRole('button', { name: 'Sign in' })
    expect(submit).toHaveClass('bg-foreground', 'text-background', 'w-full')
    expect(screen.getByTestId('login-user-name')).toHaveAttribute('placeholder', ' ')
    expect(container.querySelector('[data-slot="entry-form"]')!.className).toContain('[&_[data-slot=button].w-full]:rounded-full')
    await expectAccessible(container)
  })

  it('moves a field\'s label up and colours it while the field has the focus', async () => {
    render(<FloatingInput label="Email" type="email" />)
    const field = screen.getByLabelText('Email')
    const label = screen.getByText('Email')
    expect(label).toHaveClass('group-has-[input:placeholder-shown]:top-5', 'text-muted-foreground')
    await userEvent.click(field)
    expect(label).toHaveClass('text-primary', 'top-2', 'text-xs')
    expect(label).not.toHaveClass('group-has-[input:placeholder-shown]:top-5')
    await userEvent.tab()
    expect(label).toHaveClass('text-muted-foreground')
  })

  it('ties a refusal to its field', async () => {
    const { container } = render(<FloatingInput label="Email" error="Enter your email" />)
    const field = screen.getByLabelText('Email')
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(field).toHaveAccessibleDescription('Enter your email')
    await expectAccessible(container)
  })
})
