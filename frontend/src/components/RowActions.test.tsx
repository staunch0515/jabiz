import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import type { ProcessEntry } from '../meta/types'
import { expectAccessible } from '../test/axe'
import RowActions from './RowActions'

const post = vi.hoisted(() => vi.fn())

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { POST: (...args: unknown[]) => post(...args) },
  unwrap: async (value: unknown) => value,
}))

function process(name: string, properties: string[], values: string[]): ProcessEntry {
  return {
    name,
    version: 1,
    latest: true,
    deprecated: false,
    label: `Do ${name}`,
    description: '',
    input: { type: 'object', properties: Object.fromEntries(properties.map((p) => [p, { type: 'string' }])) },
    actsOn: { entity: 'Story', input: 'storyId', when: { field: 'status', values } },
    requiresMfa: false,
    requiresVerifiedEmail: false,
  } as ProcessEntry
}

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function renderActions(status: string, onDone = () => {}) {
  const actions = [process('SUBMIT', ['storyId'], ['DRAFT']), process('REJECT', ['storyId', 'comment'], ['SUBMITTED'])]
  return render(
    <MemoryRouter initialEntries={['/data/x']}>
      <Routes>
        <Route path="/data/x" element={<RowActions actions={actions} id="s-1" attributes={{ status }} onDone={onDone} />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('RowActions', () => {
  beforeEach(async () => {
    post.mockReset()
    await act(() => i18n.changeLanguage('en'))
  })

  it('offers the actions whose condition holds', () => {
    renderActions('DRAFT')
    expect(screen.getByTestId('row-action-SUBMIT').textContent).toBe('Do SUBMIT')
    expect(screen.queryByTestId('row-action-REJECT')).toBeNull()
  })

  it('opens the form of a process that needs more than the key, with the key filled in', () => {
    renderActions('SUBMITTED')
    expect(screen.queryByTestId('row-action-SUBMIT')).toBeNull()
    fireEvent.click(screen.getByTestId('row-action-REJECT'))
    expect(screen.getByTestId('where').textContent).toBe('/processes/REJECT/1?storyId=s-1')
  })

  it('asks before running a process that needs only the key, each run with a new idempotency key', async () => {
    post.mockResolvedValue({})
    const onDone = vi.fn()
    renderActions('DRAFT', onDone)
    await userEvent.click(screen.getByTestId('row-action-SUBMIT'))
    const confirm = screen.getByRole('alertdialog', { name: 'Run “Do SUBMIT” on this entry?' })
    await expectAccessible()
    // Cancelling runs nothing.
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(confirm).not.toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    for (let run = 0; run < 2; run++) {
      await userEvent.click(screen.getByTestId('row-action-SUBMIT'))
      await userEvent.click(screen.getByRole('button', { name: 'OK' }))
      await waitFor(() => expect(onDone).toHaveBeenCalledTimes(run + 1))
    }
    expect(post).toHaveBeenCalledTimes(2)
    const [path, first] = post.mock.calls[0] as [string, { params: { header: Record<string, string> }; body: unknown }]
    const [, second] = post.mock.calls[1] as [string, { params: { header: Record<string, string> } }]
    expect(path).toBe('/api/processes/{name}/{version}')
    expect(first.body).toEqual({ storyId: 's-1' })
    expect(first.params.header['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/)
    expect(second.params.header['Idempotency-Key']).not.toBe(first.params.header['Idempotency-Key'])
  })
})
