import { ApiError } from '@jabiz/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import i18n from '../i18n'
import { MAIL_TEXTS, SHIPPED_BODY_ZH, USER_NAMES } from '../test/mailFixtures'
import MailTextEditorPage from './MailTextEditorPage'

const get = vi.fn()
const post = vi.fn()
const runProcess = vi.fn()
const permissions = { value: new Set<string>() }

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), POST: (...args: unknown[]) => post(...args) },
  unwrap: (value: unknown) => value,
  runProcess: (...args: unknown[]) => runProcess(...args),
  useAuth: () => ({ can: (p: string) => permissions.value.has(p), userId: 'u-ann' }),
  UserName: ({ id }: { id: string }) => <span>{USER_NAMES[id] ?? id}</span>,
}))

const HISTORY = [
  {
    versionNo: 2, effectStartTime: '2026-11-02T08:00:00Z', createdTime: '2026-11-02T08:00:00Z', opTime: '2026-11-02T08:00:00Z',
    deleted: false, action: 'UPDATE', baseVersionNo: 1, changedFields: ['kind'], processSeqId: 12, actorId: 'u-bob',
    processName: 'MAIL_TEXT_RESET', reason: null, attributes: { kind: 'DEFAULT', template: 'commerce.order-shipped', locale: 'zh' },
  },
  {
    versionNo: 1, effectStartTime: '2026-11-01T08:00:00Z', createdTime: '2026-11-01T08:00:00Z', opTime: '2026-11-01T08:00:00Z',
    deleted: false, action: 'INSERT', baseVersionNo: null, changedFields: [], processSeqId: 11, actorId: 'u-ann',
    processName: 'MAIL_TEXT_SAVE', reason: null,
    attributes: { kind: 'CUSTOM', template: 'commerce.order-shipped', locale: 'zh', subject: '订单 {orderNo} 已发货', body: '订单 {orderNo} 今天发货。\n\n[退订]({unsubscribeUrl})' },
  },
]

let pending: unknown[] = []
let texts = MAIL_TEXTS

function answerGet(path: string) {
  if (path === '/api/mail/texts') return Promise.resolve(texts)
  if (path === '/api/datasets/{resourceId}/entities/{id}/history') return Promise.resolve(HISTORY)
  return Promise.reject(new Error(`unexpected GET ${path}`))
}

function answerPost(path: string, options: { params?: { path?: { resourceId?: string } }; body?: { subject?: string; body?: string } }) {
  if (path === '/api/mail/texts/preview') {
    return Promise.resolve({ subject: options.body?.subject, text: options.body?.body, html: `<p>${options.body?.body}</p>`, problems: [] })
  }
  if (path === '/api/datasets/{resourceId}/query') {
    const dataset = options.params?.path?.resourceId
    if (dataset === 'urn:jabiz:dataset:platform:SysControlChange') return Promise.resolve({ items: pending })
    return Promise.resolve({ items: [{ id: 'text-1', attributes: {} }] })
  }
  return Promise.reject(new Error(`unexpected POST ${path}`))
}

function show(template: string, locale: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[`/mail/texts/edit?template=${template}&locale=${locale}`]}>
        <MailTextEditorPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const previews = () => post.mock.calls.filter(([path]) => path === '/api/mail/texts/preview').map(([, o]) => o.body)

describe('MailTextEditorPage', () => {
  beforeEach(async () => {
    get.mockReset()
    post.mockReset()
    runProcess.mockReset()
    get.mockImplementation(answerGet)
    post.mockImplementation(answerPost)
    pending = []
    texts = MAIL_TEXTS
    permissions.value = new Set(['mail.text.read', 'mail.text.write', 'control.propose', 'approval.read'])
    await act(() => i18n.changeLanguage('en'))
  })

  it('edits a language\'s texts, flags missing required placeholders and inserts placeholders at the cursor', async () => {
    show('commerce.order-shipped', 'zh')
    expect(await screen.findByRole('heading', { level: 1, name: 'Order shipped' })).toBeInTheDocument()
    const body = screen.getByTestId('mail-body') as HTMLTextAreaElement
    expect(body.value).toBe(SHIPPED_BODY_ZH)
    expect(screen.getByTestId('mail-required-orderNo')).not.toHaveAttribute('data-missing')
    expect(screen.queryByTestId('mail-missing')).toBeNull()

    fireEvent.change(screen.getByTestId('mail-subject'), { target: { value: 'Shipped' } })
    fireEvent.change(body, { target: { value: 'Shipped.' } })
    expect(screen.getByTestId('mail-required-orderNo')).toHaveAttribute('data-missing', 'true')
    expect(screen.getByTestId('mail-required-unsubscribeUrl')).toHaveTextContent('missing')
    expect(screen.getByTestId('mail-missing')).toHaveTextContent('{orderNo} {unsubscribeUrl}')

    // Put at the cursor: after "Shipped".
    body.focus()
    body.setSelectionRange(7, 7)
    await userEvent.setup().click(screen.getByTestId('mail-insert-orderNo'))
    expect(body.value).toBe('Shipped{orderNo}.')
    expect(screen.getByTestId('mail-required-orderNo')).not.toHaveAttribute('data-missing')
    await expectAccessible()
  })

  it('previews the draft once typing pauses, in a sandboxed frame without scripts', async () => {
    show('commerce.order-shipped', 'zh')
    const body = (await screen.findByTestId('mail-body')) as HTMLTextAreaElement
    await waitFor(() => expect(previews()).toHaveLength(1))
    expect(previews()[0]).toEqual({ template: 'commerce.order-shipped', locale: 'zh', subject: '订单 {orderNo} 已发货', body: SHIPPED_BODY_ZH })

    fireEvent.change(body, { target: { value: 'a {orderNo}' } })
    fireEvent.change(body, { target: { value: 'ab {orderNo}' } })
    fireEvent.change(body, { target: { value: 'abc {orderNo} {unsubscribeUrl}' } })
    await waitFor(() => expect(previews().at(-1)?.body).toBe('abc {orderNo} {unsubscribeUrl}'))
    // Only the settled draft was asked for, not every keystroke.
    expect(previews().map((b) => b.body)).not.toContain('a {orderNo}')
    expect(previews().map((b) => b.body)).not.toContain('ab {orderNo}')
    const frame = await screen.findByTestId('mail-preview-frame')
    await waitFor(() => expect(frame).toHaveAttribute('srcdoc', '<p>abc {orderNo} {unsubscribeUrl}</p>'))
    expect(frame).toHaveAttribute('sandbox', '')
  })

  it('saves with the version read, explains a conflict and reloads', async () => {
    show('commerce.order-shipped', 'ja')
    const subject = (await screen.findByTestId('mail-subject')) as HTMLInputElement
    expect(subject.value).toBe('発送のお知らせ {orderNo}')
    expect(screen.getByTestId('mail-save')).toBeDisabled()
    fireEvent.change(subject, { target: { value: '発送 {orderNo}' } })
    runProcess.mockRejectedValueOnce(new ApiError(409, { detail: 'version 4, not 3' }))
    await userEvent.setup().click(screen.getByTestId('mail-save'))
    expect(runProcess).toHaveBeenCalledWith('MAIL_TEXT_SAVE', {
      template: 'commerce.order-shipped', locale: 'ja', subject: '発送 {orderNo}',
      body: 'ご注文 {orderNo} を本日発送しました。\n\n[停止]({unsubscribeUrl})', baseVersionNo: 3,
    })
    expect(await screen.findByTestId('mail-conflict')).toHaveTextContent('Someone else changed this text after you opened it')

    const calls = get.mock.calls.filter(([path]) => path === '/api/mail/texts').length
    await userEvent.setup().click(screen.getByTestId('mail-conflict-reload'))
    await waitFor(() => expect(get.mock.calls.filter(([path]) => path === '/api/mail/texts').length).toBe(calls + 1))
    await waitFor(() => expect((screen.getByTestId('mail-subject') as HTMLInputElement).value).toBe('発送のお知らせ {orderNo}'))
    expect(screen.queryByTestId('mail-conflict')).toBeNull()
  })

  it('shows the rules\' problems next to the fields', async () => {
    show('commerce.order-shipped', 'zh')
    const body = await screen.findByTestId('mail-body')
    fireEvent.change(body, { target: { value: '{foo} {orderNo} {unsubscribeUrl}' } })
    runProcess.mockRejectedValueOnce(new ApiError(400, {
      violations: [
        { field: 'body', message: '{foo} is not a placeholder of this mail', ruleCode: 'MAIL_TEXT_UNKNOWN_PLACEHOLDER' },
        { field: 'subject', message: 'The subject is one line', ruleCode: 'MAIL_TEXT_SUBJECT_LINE' },
      ],
    }))
    await userEvent.setup().click(screen.getByTestId('mail-save'))
    expect(await screen.findByTestId('mail-body-problems')).toHaveTextContent('{foo} is not a placeholder of this mail')
    expect(screen.getByTestId('mail-subject-problems')).toHaveTextContent('The subject is one line')
    expect(screen.getByTestId('mail-body')).toHaveAttribute('aria-invalid', 'true')
  })

  it('proposes a change of a template that needs approval instead of saving it, and shows the pending one', async () => {
    pending = [{ id: 'c-9', attributes: { targetKey: 'jabiz.password-reset [en]', proposedBy: 'u-bob', reason: 'Friendlier tone',
      status: 'PROPOSED', changeValues: JSON.stringify({ template: 'jabiz.password-reset', locale: 'en', subject: 'Reset it', body: '[Reset]({entryUrl}reset?token={resetToken})' }) } }]
    runProcess.mockResolvedValue({ changeId: 'c-10', status: 'PROPOSED' })
    show('jabiz.password-reset', 'en')
    expect(await screen.findByTestId('mail-protected')).toHaveTextContent('need another administrator\'s approval')
    expect(screen.queryByTestId('mail-save')).toBeNull()
    const change = await screen.findByTestId('mail-pending-change')
    expect(change).toHaveTextContent('Proposed by Bob Wu')
    expect(change).toHaveTextContent('Friendlier tone')
    expect(within(change).getByTestId('mail-pending-link')).toHaveAttribute(
      'href', `/data/${encodeURIComponent('urn:jabiz:dataset:platform:SysControlChange')}`)
    expect(screen.getByTestId('mail-state-en')).toHaveTextContent('Pending approval')
    // Not someone else's proposal to withdraw.
    expect(within(change).queryByTestId('mail-pending-withdraw')).toBeNull()
    expect(post).toHaveBeenCalledWith('/api/datasets/{resourceId}/query', expect.objectContaining({
      body: expect.objectContaining({ filters: expect.arrayContaining([{ field: 'targetKey', op: 'eq', value: 'jabiz.password-reset [en]' }]) }),
    }))

    const user = userEvent.setup()
    fireEvent.change(screen.getByTestId('mail-subject'), { target: { value: 'Reset your password now' } })
    await user.click(screen.getByTestId('mail-propose-submit'))
    expect(await screen.findByText('Give a reason.')).toBeInTheDocument()
    expect(runProcess).not.toHaveBeenCalled()
    await user.type(screen.getByTestId('mail-propose-reason'), 'clearer')
    await user.click(screen.getByTestId('mail-propose-submit'))
    await waitFor(() => expect(runProcess).toHaveBeenCalledWith('CONTROL_CHANGE_PROPOSE', {
      targetEntity: 'MailText',
      values: { template: 'jabiz.password-reset', locale: 'en', subject: 'Reset your password now', body: '[Reset]({entryUrl}reset?token={resetToken})' },
      reason: 'clearer',
    }))
    await expectAccessible()
  })

  it('restores the default only after the in-page question', async () => {
    runProcess.mockResolvedValue({ kind: 'DEFAULT', versionNo: 4 })
    show('commerce.order-shipped', 'ja')
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('mail-reset'))
    let dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Restore the default text?')
    await expectAccessible()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(runProcess).not.toHaveBeenCalled()

    await user.click(screen.getByTestId('mail-reset'))
    dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Restore default' }))
    await waitFor(() => expect(runProcess).toHaveBeenCalledWith('MAIL_TEXT_RESET',
      { template: 'commerce.order-shipped', locale: 'ja', baseVersionNo: 3 }))
  })

  it('sends a test mail of the draft to oneself', async () => {
    runProcess.mockResolvedValue({ messageId: 'm-1', address: 'ann@example.com' })
    show('commerce.order-shipped', 'zh')
    await userEvent.setup().click(await screen.findByTestId('mail-test-send'))
    expect(runProcess).toHaveBeenCalledWith('MAIL_TEXT_TEST_SEND',
      { template: 'commerce.order-shipped', locale: 'zh', subject: '订单 {orderNo} 已发货', body: SHIPPED_BODY_ZH })
  })

  it('lists the history, compares a version with what is in effect and takes it as the draft', async () => {
    show('commerce.order-shipped', 'zh')
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('mail-version-open-1'))
    const compare = screen.getByTestId('mail-version-compare')
    expect(compare).toHaveTextContent('Version 1 compared with what is in effect now')
    expect(screen.getByText('Ann Lee')).toBeInTheDocument()
    expect(screen.getByText('Bob Wu')).toBeInTheDocument()
    expect(screen.getByText('Default restored')).toBeInTheDocument()
    // The subject is the same; the body's first line differs.
    expect(within(compare).getByTestId('mail-diff-subject')).toHaveTextContent('No difference.')
    const bodyDiff = within(compare).getByTestId('mail-diff-body')
    expect(bodyDiff.querySelector('[data-kind="removed"]')).toHaveTextContent('订单 {orderNo} 今天发货。')
    expect(bodyDiff.querySelector('[data-kind="added"]')).toHaveTextContent('您的订单 {orderNo} 已发货。')
    await expectAccessible()

    await user.click(within(compare).getByTestId('mail-version-use'))
    expect((screen.getByTestId('mail-body') as HTMLTextAreaElement).value).toBe('订单 {orderNo} 今天发货。\n\n[退订]({unsubscribeUrl})')
    expect(screen.getByTestId('mail-save')).toBeEnabled()
    expect(get).toHaveBeenCalledWith('/api/datasets/{resourceId}/entities/{id}/history',
      { params: { path: { resourceId: 'urn:jabiz:dataset:platform:MailText', id: 'text-1' } } })
  })

  it('switches languages by their tabs, keeping each draft', async () => {
    show('commerce.order-shipped', 'zh')
    const user = userEvent.setup()
    fireEvent.change(await screen.findByTestId('mail-subject'), { target: { value: 'changed {orderNo}' } })
    await user.click(screen.getByTestId('mail-tab-en'))
    expect(await screen.findByTestId('mail-stored-problems')).toHaveTextContent('{gone} is not a placeholder of this mail')
    expect((screen.getByTestId('mail-subject') as HTMLInputElement).value).toBe('Shipped')
    await user.click(screen.getByTestId('mail-tab-zh'))
    expect((await screen.findByTestId('mail-subject') as HTMLInputElement).value).toBe('changed {orderNo}')
  })

  it('offers no writing to those who may only read', async () => {
    permissions.value = new Set(['mail.text.read'])
    show('commerce.order-shipped', 'zh')
    expect(await screen.findByTestId('mail-body')).toHaveAttribute('readonly')
    expect(screen.queryByTestId('mail-save')).toBeNull()
    expect(screen.queryByTestId('mail-test-send')).toBeNull()
    expect(screen.queryByTestId('mail-version-use')).toBeNull()
    expect(screen.getByTestId('mail-insert-orderNo')).toBeDisabled()
  })
  it('proposes an unapproved text as it is, without an edit', async () => {
    runProcess.mockResolvedValue({ changeId: 'c-11', status: 'PROPOSED' })
    show('jabiz.password-reset', 'ja')
    const submit = await screen.findByTestId('mail-propose-submit')
    expect(submit).toBeEnabled()
    const user = userEvent.setup()
    await user.type(screen.getByTestId('mail-propose-reason'), 'approve the stored text')
    await user.click(submit)
    await waitFor(() => expect(runProcess).toHaveBeenCalledWith('CONTROL_CHANGE_PROPOSE', {
      targetEntity: 'MailText',
      values: { template: 'jabiz.password-reset', locale: 'ja', subject: '再設定', body: '[こちら]({entryUrl}reset?token={resetToken})' },
      reason: 'approve the stored text',
    }))
  })

  it('saves a text whose default changed unchanged, to confirm it against the new default', async () => {
    texts = {
      ...MAIL_TEXTS,
      templates: (MAIL_TEXTS.templates ?? []).map((template) => template.template !== 'commerce.order-shipped' ? template : {
        ...template,
        languages: (template.languages ?? []).map((language) => language.locale !== 'ja' ? language : { ...language, state: 'DEFAULT_UPDATED' as const }),
      }),
    }
    runProcess.mockResolvedValue({ kind: 'CUSTOM', versionNo: 9 })
    show('commerce.order-shipped', 'ja')
    const save = await screen.findByTestId('mail-save')
    expect(save).toBeEnabled()
    await userEvent.setup().click(save)
    await waitFor(() => expect(runProcess).toHaveBeenCalledWith('MAIL_TEXT_SAVE', expect.objectContaining({ template: 'commerce.order-shipped', locale: 'ja' })))
  })

  it('names the sides of a pending change for screen readers as the text in effect and the proposal', async () => {
    pending = [{ id: 'c-9', attributes: { targetKey: 'jabiz.password-reset [en]', proposedBy: 'u-bob', reason: 'Friendlier tone',
      status: 'PROPOSED', changeValues: JSON.stringify({ template: 'jabiz.password-reset', locale: 'en', subject: 'Reset it', body: '[Reset]({entryUrl}reset?token={resetToken})' }) } }]
    show('jabiz.password-reset', 'en')
    const change = await screen.findByTestId('mail-pending-change')
    expect(change).toHaveTextContent('only in the proposal: Reset it')
    expect(change).toHaveTextContent('only in the text in effect: Reset your password')
    expect(change).not.toHaveTextContent('only now')
  })
})
