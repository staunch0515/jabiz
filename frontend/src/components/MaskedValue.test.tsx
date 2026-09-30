import { fireEvent, render, screen } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
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
    await i18n.changeLanguage('en')
  })

  it('asks the server for the value once shown, which records it', async () => {
    post.mockResolvedValue({ value: 'DE44500105175407324931' })
    render(
      <App>
        <MaskedValue datasetId="urn:d" id="c1" field="bankAccount" masked="****4931" />
      </App>,
    )
    expect(screen.getByText('****4931')).toBeTruthy()
    expect(post).not.toHaveBeenCalled()

    fireEvent.click(screen.getByTestId('reveal-bankAccount'))
    expect(await screen.findByText('DE44500105175407324931')).toBeTruthy()
    expect(post).toHaveBeenCalledWith('/api/datasets/{resourceId}/reveal', {
      params: { path: { resourceId: 'urn:d' } },
      body: { id: 'c1', field: 'bankAccount' },
    })
  })

  it('keeps the masked form when the server refuses', async () => {
    post.mockRejectedValue(new Error('403'))
    render(
      <App>
        <MaskedValue datasetId="urn:d" id="c1" field="bankAccount" masked="****4931" />
      </App>,
    )
    fireEvent.click(screen.getByTestId('reveal-bankAccount'))
    expect(await screen.findByText('Error: 403')).toBeTruthy()
    expect(screen.getByText('****4931')).toBeTruthy()
  })
})
