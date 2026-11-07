import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import i18n from '../i18n'
import { MAIL_TEXTS } from '../test/mailFixtures'
import MailTextsPage from './MailTextsPage'

const get = vi.fn()
const post = vi.fn()
const permissions = { value: new Set<string>() }

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), POST: (...args: unknown[]) => post(...args) },
  unwrap: (value: unknown) => value,
  useAuth: () => ({ can: (p: string) => permissions.value.has(p), userId: 'u-ann' }),
}))

function show() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <MailTextsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('MailTextsPage', () => {
  beforeEach(async () => {
    get.mockReset()
    post.mockReset()
    get.mockResolvedValue(MAIL_TEXTS)
    // One change of the password reset mail's English text waits for approval.
    post.mockResolvedValue({
      items: [{ id: 'c-1', attributes: { targetKey: 'jabiz.password-reset [en]', proposedBy: 'u-bob', reason: 'tone', status: 'PROPOSED' } }],
    })
    permissions.value = new Set(['mail.text.read', 'mail.text.write', 'approval.read'])
    await act(() => i18n.changeLanguage('en'))
  })

  it('lists every template with its category, whether changes need approval and each language\'s state', async () => {
    show()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Mail templates')
    const shipped = await screen.findByTestId('mail-row-commerce.order-shipped')
    expect(get).toHaveBeenCalledWith('/api/mail/texts')
    expect(within(shipped).getByRole('link', { name: 'Order shipped' })).toHaveAttribute(
      'href', '/mail/texts/edit?template=commerce.order-shipped')
    expect(shipped).toHaveTextContent('Notification')
    expect(shipped).toHaveTextContent('Take effect when saved')
    expect(within(shipped).getByTestId('mail-state-zh')).toHaveTextContent('Default')
    expect(within(shipped).getByTestId('mail-state-ja')).toHaveTextContent('Customised')
    expect(within(shipped).getByTestId('mail-state-en')).toHaveTextContent('Invalid (default sent)')

    const reset = screen.getByTestId('mail-row-jabiz.password-reset')
    expect(reset).toHaveTextContent('Transactional')
    expect(within(reset).getByTestId('mail-needs-approval')).toHaveTextContent('Need approval')
    expect(within(reset).getByTestId('mail-state-zh')).toHaveTextContent('Default updated')
    expect(within(reset).getByTestId('mail-state-ja')).toHaveTextContent('Not approved (default sent)')
    // The open controlled change: pending approval.
    await waitFor(() => expect(within(reset).getByTestId('mail-state-en')).toHaveTextContent('Pending approval'))
    expect(post).toHaveBeenCalledWith('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: 'urn:jabiz:dataset:platform:SysControlChange' } },
      body: {
        filters: [{ field: 'targetEntity', op: 'eq', value: 'MailText' }, { field: 'status', op: 'eq', value: 'PROPOSED' }],
        limit: 200,
      },
    })
    await expectAccessible()
  })

  it('does not ask for the pending changes without the right to read them', async () => {
    permissions.value = new Set(['mail.text.read'])
    show()
    const reset = await screen.findByTestId('mail-row-jabiz.password-reset')
    expect(within(reset).getByTestId('mail-state-en')).toHaveTextContent('Default')
    expect(post).not.toHaveBeenCalled()
  })

  it('names the templates in Chinese and Japanese', async () => {
    await act(() => i18n.changeLanguage('zh'))
    show()
    expect(await screen.findByRole('heading', { level: 1, name: '邮件模版' })).toBeInTheDocument()
    const shipped = await screen.findByTestId('mail-row-commerce.order-shipped')
    expect(within(shipped).getByTestId('mail-state-ja')).toHaveTextContent('已定制')
    await act(() => i18n.changeLanguage('ja'))
    expect(await screen.findByRole('heading', { level: 1, name: 'メールテンプレート' })).toBeInTheDocument()
  })
})
