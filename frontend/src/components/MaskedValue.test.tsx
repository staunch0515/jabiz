import { notify } from '@jabiz/ui'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '../test/axe'
import MaskedValue from './MaskedValue'

const post = vi.fn()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { POST: (...args: unknown[]) => post(...args) },
  unwrap: async (value: unknown) => value,
}))

describe('MaskedValue', () => {
  beforeEach(async () => {
    post.mockReset()
    vi.restoreAllMocks()
    await i18n.changeLanguage('en')
  })

  it('asks the server for the value once shown, which records it', async () => {
    post.mockResolvedValue({ value: 'DE44500105175407324931' })
    const { container } = render(<MaskedValue datasetId="urn:d" id="c1" field="bankAccount" masked="****4931" />)
    expect(screen.getByText('****4931')).toBeTruthy()
    expect(post).not.toHaveBeenCalled()
    await expectAccessible(container)

    fireEvent.click(screen.getByRole('button', { name: 'Show' }))
    expect(await screen.findByText('DE44500105175407324931')).toBeTruthy()
    expect(screen.getByTestId('revealed-bankAccount')).toHaveTextContent('DE44500105175407324931')
    expect(post).toHaveBeenCalledWith('/api/datasets/{resourceId}/reveal', {
      params: { path: { resourceId: 'urn:d' } },
      body: { id: 'c1', field: 'bankAccount' },
    })
  })

  it('asks again each time it is shown anew', async () => {
    post.mockResolvedValue({ value: 'DE44500105175407324931' })
    const first = render(<MaskedValue datasetId="urn:d" id="c1" field="bankAccount" masked="****4931" />)
    fireEvent.click(screen.getByTestId('reveal-bankAccount'))
    await screen.findByText('DE44500105175407324931')
    // The plain value lives in the component only: shown again after the page was left, it is asked for again.
    first.unmount()
    render(<MaskedValue datasetId="urn:d" id="c1" field="bankAccount" masked="****4931" />)
    expect(screen.queryByText('DE44500105175407324931')).toBeNull()
    fireEvent.click(screen.getByTestId('reveal-bankAccount'))
    await screen.findByText('DE44500105175407324931')
    expect(post).toHaveBeenCalledTimes(2)
  })

  it('forgets the shown value when the masked value (or the entry) changes', async () => {
    post.mockResolvedValue({ value: 'DE44500105175407324931' })
    const { rerender } = render(<MaskedValue datasetId="urn:d" id="c1" field="bankAccount" masked="****4931" />)
    fireEvent.click(screen.getByTestId('reveal-bankAccount'))
    await screen.findByText('DE44500105175407324931')
    // The value was changed (read again): the old plain text must not stand for the new one.
    rerender(<MaskedValue datasetId="urn:d" id="c1" field="bankAccount" masked="****1234" />)
    expect(screen.queryByText('DE44500105175407324931')).toBeNull()
    expect(screen.getByText('****1234')).toBeInTheDocument()
    fireEvent.click(screen.getByTestId('reveal-bankAccount'))
    await screen.findByText('DE44500105175407324931')
    rerender(<MaskedValue datasetId="urn:d" id="c2" field="bankAccount" masked="****1234" />)
    expect(screen.queryByText('DE44500105175407324931')).toBeNull()
  })

  it('keeps the masked form when the server refuses', async () => {
    const error = vi.spyOn(notify, 'error')
    post.mockRejectedValue(new Error('403'))
    render(<MaskedValue datasetId="urn:d" id="c1" field="bankAccount" masked="****4931" />)
    fireEvent.click(screen.getByTestId('reveal-bankAccount'))
    await waitFor(() => expect(error).toHaveBeenCalledWith('Error: 403'))
    expect(screen.getByText('****4931')).toBeTruthy()
    expect(screen.getByTestId('reveal-bankAccount')).toBeEnabled()
  })
})
