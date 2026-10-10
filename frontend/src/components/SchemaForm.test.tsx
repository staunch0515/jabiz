import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { inputNodes, toProcessInput, type JsonSchema } from '../meta/processForm'
import { expectAccessible } from '../test/axe'
import SchemaForm from './SchemaForm'

const schema: JsonSchema = {
  type: 'object',
  properties: {
    secret: { type: 'string', format: 'password' },
    at: { type: 'string', format: 'date-time' },
    day: { type: 'string', format: 'date' },
    ratio: { type: 'number' },
    flag: { type: 'boolean' },
    confirmed: { type: 'boolean' },
    direction: { type: 'string', enum: ['DEBIT', 'CREDIT'] },
    labels: { type: 'array', items: { type: 'string' } },
    address: { type: 'object', properties: { city: { type: 'string' } }, required: ['city'] },
    entries: {
      type: 'array',
      items: { type: 'object', properties: { account: { type: 'string' } }, required: ['account'] },
    },
    extra: {},
  },
  required: ['direction', 'confirmed', 'entries'],
}
const nodes = inputNodes(schema)

describe('SchemaForm', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('offers a control per kind, groups objects and lists, and is accessible', async () => {
    render(<SchemaForm nodes={nodes} submitLabel="Run" onSubmit={() => {}} />)
    expect(screen.getByLabelText('secret')).toHaveAttribute('type', 'password')
    expect(screen.getByLabelText('secret')).toHaveAttribute('autocomplete', 'new-password')
    expect(screen.getByLabelText('at')).toHaveAttribute('placeholder', 'YYYY-MM-DD HH:mm:ss')
    expect(screen.getByLabelText('day')).toHaveAttribute('placeholder', 'YYYY-MM-DD')
    expect(screen.getByLabelText('ratio')).toHaveAttribute('type', 'number')
    expect(screen.getByRole('switch', { name: 'flag' })).not.toBeChecked()
    const direction = screen.getByRole('combobox', { name: /direction/ })
    expect(direction).toHaveAttribute('aria-required', 'true')
    expect(screen.getByRole('group', { name: 'address' })).toContainElement(screen.getByLabelText(/city/))
    // A required list starts with one item.
    expect(within(screen.getByRole('group', { name: 'entries' })).getAllByLabelText(/account/)).toHaveLength(1)
    expect(screen.getByLabelText('extra')).toHaveAttribute('placeholder', 'JSON')
    await expectAccessible()
  })

  it('gives the values in the form the request body is made from', async () => {
    const onSubmit = vi.fn()
    render(<SchemaForm nodes={nodes} submitLabel="Run" onSubmit={onSubmit} />)
    await userEvent.type(screen.getByLabelText('at'), '2026-01-31 15:00{Enter}')
    await userEvent.type(screen.getByLabelText('day'), '2026-02-01')
    await userEvent.type(screen.getByLabelText('ratio'), '0.25')
    await userEvent.click(screen.getByRole('switch', { name: 'flag' }))
    await userEvent.click(screen.getByRole('combobox', { name: /direction/ }))
    await userEvent.click(screen.getByRole('option', { name: 'CREDIT' }))
    await userEvent.type(screen.getByLabelText('labels'), 'a{Enter}b{Enter}')
    await userEvent.type(screen.getByLabelText(/city/), 'Tokyo')
    await userEvent.type(screen.getByLabelText(/account/), '5100')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    await userEvent.type(screen.getAllByLabelText(/account/)[1], '1110')
    await userEvent.type(screen.getByLabelText('extra'), '{{"x": 1}')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(onSubmit).toHaveBeenCalledOnce()
    expect(toProcessInput(nodes, onSubmit.mock.calls[0][0])).toEqual({
      at: new Date(2026, 0, 31, 15, 0, 0).toISOString(),
      day: '2026-02-01',
      ratio: 0.25,
      flag: true,
      confirmed: false,
      direction: 'CREDIT',
      labels: ['a', 'b'],
      address: { city: 'Tokyo' },
      entries: [{ account: '5100' }, { account: '1110' }],
      extra: { x: 1 },
    })
  })

  it('checks required inputs inside objects and list items, and removes items', async () => {
    const onSubmit = vi.fn()
    render(<SchemaForm nodes={nodes} submitLabel="Run" onSubmit={onSubmit} />)
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(screen.getByText('direction: REQUIRED')).toBeInTheDocument()
    expect(screen.getByText('city: REQUIRED')).toBeInTheDocument()
    expect(screen.getAllByText('account: REQUIRED')).toHaveLength(2)
    expect(screen.getByRole('combobox', { name: /direction/ })).toHaveAttribute('aria-invalid', 'true')
    expect(onSubmit).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Remove item 2 of entries' }))
    expect(screen.getAllByText('account: REQUIRED')).toHaveLength(1)
    await expectAccessible()
  })

  it('is not sent while a date field holds text that is no date', async () => {
    const onSubmit = vi.fn()
    const dates = inputNodes({
      type: 'object',
      properties: { at: { type: 'string', format: 'date-time' }, day: { type: 'string', format: 'date' } },
    })
    render(<SchemaForm nodes={dates} submitLabel="Run" onSubmit={onSubmit} />)
    await userEvent.type(screen.getByLabelText('day'), '2026-13-01')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByLabelText('day')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('day')).toHaveFocus()
    // The field says why, once.
    expect(screen.getByLabelText('day')).toHaveAccessibleDescription('Not a valid date (YYYY-MM-DD).')
    expect(screen.queryByText('day: INVALID_VALUE')).toBeNull()
    await expectAccessible()

    await userEvent.clear(screen.getByLabelText('day'))
    await userEvent.type(screen.getByLabelText('day'), '2026-12-01')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ day: '2026-12-01' }))
  })

  it('shows inputs filled in from the row as read-only', () => {
    render(<SchemaForm nodes={inputNodes({ type: 'object', properties: { id: { type: 'string' } } })} preset={{ id: 'x-1' }}
      submitLabel="Run" onSubmit={() => {}} />)
    expect(screen.getByLabelText('id')).toHaveValue('x-1')
    expect(screen.getByLabelText('id')).toHaveAttribute('readonly')
  })
})
