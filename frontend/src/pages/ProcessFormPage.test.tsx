import { session, setStepUpHandler } from '@jabiz/client'
import { notify } from '@jabiz/ui'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import type { JsonSchema } from '../meta/processForm'
import { expectAccessible } from '../test/axe'
import ProcessFormPage from './ProcessFormPage'

const input: JsonSchema = {
  type: 'object',
  properties: {
    priceId: { type: 'string', format: 'uuid' },
    percent: { type: ['string', 'number'], format: 'decimal' },
    count: { type: 'integer' },
    note: { type: 'string', pattern: '^[A-Z]' },
    data: {},
  },
  required: ['priceId', 'percent'],
}

const processes = vi.hoisted(() => ({ value: { data: [] as unknown[], isLoading: false } }))
vi.mock('../meta/hooks', () => ({ useProcesses: () => processes.value }))

const MFA_REQUIRED = { status: 403, violations: [{ ruleCode: 'MFA_REQUIRED', message: 'confirm' }] }
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** The requests the page sent: their idempotency keys and bodies. */
const sent: { key: string | null; body: unknown }[] = []
let answers: Response[] = []

function page(path = '/processes/PRICE_ADJUST/1') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/processes/:name/:version" element={<ProcessFormPage />} />
        <Route path="/processes" element={<p>catalog</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProcessFormPage', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    processes.value = {
      isLoading: false,
      data: [
        {
          name: 'PRICE_ADJUST',
          version: 1,
          label: 'Adjust a price',
          description: 'Changes a price by a percentage.',
          input,
          actsOn: { entity: 'Price', input: 'priceId' },
        },
      ],
    }
    sent.length = 0
    answers = []
    session.store('access', 'refresh')
    vi.stubGlobal(
      'fetch',
      vi.fn(async (request: Request) => {
        sent.push({ key: request.headers.get('Idempotency-Key'), body: await request.clone().json() })
        return answers.shift() ?? json(200, { processSeqId: 77, output: { amount: '110' } })
      }),
    )
  })

  afterEach(() => {
    setStepUpHandler(null)
    session.clear()
    vi.unstubAllGlobals()
  })

  it('runs the process with typed values as JSON, shows the result and takes a new key after a success', async () => {
    page()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Adjust a price')
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Processes' })).toHaveAttribute('href', '/processes')
    await userEvent.type(screen.getByLabelText(/priceId/), 'p-1')
    await userEvent.type(screen.getByLabelText(/percent/), '10.50')
    await userEvent.type(screen.getByLabelText(/count/), '3')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(await screen.findByTestId('process-result')).toHaveTextContent('Operation #77')
    expect(screen.getByTestId('process-result')).toHaveTextContent('"amount": "110"')
    expect(sent).toHaveLength(1)
    expect(sent[0].body).toEqual({ priceId: 'p-1', percent: '10.50', count: 3 })
    expect(sent[0].key).toMatch(/^[0-9a-f-]{36}$/)

    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1].key).not.toBe(sent[0].key)
    await expectAccessible()
  })

  it('refuses to send without the required inputs or with text not matching the pattern', async () => {
    page()
    await userEvent.type(screen.getByLabelText(/note/), 'lower')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(await screen.findByText('priceId: REQUIRED')).toBeInTheDocument()
    expect(screen.getByText('percent: REQUIRED')).toBeInTheDocument()
    expect(screen.getByText('note: INVALID_VALUE')).toHaveAttribute('data-rule-code', 'INVALID_VALUE')
    expect(screen.getByLabelText(/priceId/)).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText(/priceId/)).toHaveAccessibleDescription('priceId: REQUIRED')
    expect(screen.getByLabelText(/priceId/)).toHaveFocus()
    expect(sent).toHaveLength(0)
    // Fixed as the user types.
    await userEvent.type(screen.getByLabelText(/priceId/), 'p')
    expect(screen.queryByText('priceId: REQUIRED')).toBeNull()
    await expectAccessible()
  })

  it('lists the violations the server answers with, keeps the key after a failure, and reports unreadable JSON', async () => {
    answers = [json(422, { violations: [{ field: 'percent', ruleCode: 'RANGE', message: 'Too much.' }] })]
    page()
    await userEvent.type(screen.getByLabelText(/priceId/), 'p-1')
    await userEvent.type(screen.getByLabelText(/percent/), '900')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    const error = await screen.findByTestId('process-error')
    expect(within(error).getByText('percent: Too much.')).toHaveAttribute('data-rule-code', 'RANGE')

    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    await screen.findByTestId('process-result')
    expect(sent[1].key).toBe(sent[0].key)

    await userEvent.type(screen.getByLabelText(/data/), '{{oops')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(await screen.findByText('data: Not valid JSON.')).toBeInTheDocument()
    expect(sent).toHaveLength(2)
  })

  it('says when the server cannot be reached, keeps the key and lets the user try again', async () => {
    const error = vi.spyOn(notify, 'error')
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))
    page()
    await userEvent.type(screen.getByLabelText(/priceId/), 'p-1')
    await userEvent.type(screen.getByLabelText(/percent/), '5')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    await waitFor(() => expect(error).toHaveBeenCalledWith('Failed to fetch'))
    expect(screen.queryByTestId('process-result')).toBeNull()
    expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled()
    error.mockRestore()
  })

  it('sends a request answered with a step-up again with the same key', async () => {
    answers = [json(403, MFA_REQUIRED)]
    const stepUp = vi.fn(async () => true)
    setStepUpHandler(stepUp)
    page()
    await userEvent.type(screen.getByLabelText(/priceId/), 'p-1')
    await userEvent.type(screen.getByLabelText(/percent/), '5')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    await screen.findByTestId('process-result')
    expect(stepUp).toHaveBeenCalledOnce()
    expect(sent).toHaveLength(2)
    expect(sent[1].key).toBe(sent[0].key)
  })

  it('fills in and fixes the key of the row an action was started on', () => {
    page('/processes/PRICE_ADJUST/1?priceId=p-9&percent=3')
    expect(screen.getByLabelText(/priceId/)).toHaveValue('p-9')
    expect(screen.getByLabelText(/priceId/)).toHaveAttribute('readonly')
    // Only the input the action names is taken from the address.
    expect(screen.getByLabelText(/percent/)).toHaveValue('')
  })

  it('says when the process does not exist, and while loading', () => {
    page('/processes/NOPE/1')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('NOPE@1')
    processes.value = { data: [], isLoading: true }
    page('/processes/NOPE/1')
    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
  })
})
