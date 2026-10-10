import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import { FileUpload, fileMatchesAccept } from './file-upload'

const png = () => new File(['png'], 'logo.png', { type: 'image/png' })

describe('FileUpload', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('is a real, named file input that uploads the chosen file and shows it is busy meanwhile', async () => {
    let finish: () => void = () => {}
    const onUpload = vi.fn(() => new Promise<void>((resolve) => (finish = resolve)))
    render(<FileUpload onUpload={onUpload} accept="image/*" hint="Images up to 1 MB" />)
    const input = screen.getByLabelText('Choose a file')
    expect(input).toHaveAttribute('type', 'file')
    expect(input).toHaveAccessibleDescription('Images up to 1 MB')
    await userEvent.upload(input, png())
    expect(onUpload).toHaveBeenCalledWith(expect.objectContaining({ name: 'logo.png' }))
    expect(screen.getByRole('status')).toHaveTextContent('Uploading…')
    expect(input).toBeDisabled()
    await act(async () => finish())
    expect(screen.queryByRole('status')).toBeNull()
    await expectAccessible()
  })

  it('refuses a file of another type or too large before uploading, with an alert', async () => {
    const onUpload = vi.fn()
    render(<FileUpload onUpload={onUpload} accept=".csv,text/csv" maxSize={2} maxSizeText="2 B" />)
    const input = screen.getByLabelText('Choose a file')
    // Testing Library's upload honours accept; the check is also the component's own.
    await userEvent.upload(input, png(), { applyAccept: false })
    expect(screen.getByRole('alert')).toHaveTextContent('Files of this type are not accepted (.csv,text/csv).')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    await userEvent.upload(input, new File(['a,b,c'], 'rows.csv', { type: 'text/csv' }))
    expect(screen.getByRole('alert')).toHaveTextContent('The file is larger than 2 B.')
    expect(onUpload).not.toHaveBeenCalled()
    await expectAccessible()
  })

  it('shows why an upload failed', async () => {
    render(<FileUpload onUpload={() => Promise.reject(new Error('Not allowed.'))} label="Upload" />)
    await userEvent.upload(screen.getByLabelText('Upload'), png())
    expect(await screen.findByRole('alert')).toHaveTextContent('Not allowed.')
  })

  it('matches types by media type, wildcard and extension', () => {
    expect(fileMatchesAccept({ name: 'a.PNG', type: 'image/png' }, 'image/*')).toBe(true)
    expect(fileMatchesAccept({ name: 'a.xlsx', type: '' }, '.xlsx')).toBe(true)
    expect(fileMatchesAccept({ name: 'a.txt', type: 'text/plain' }, 'application/pdf, .csv')).toBe(false)
    expect(fileMatchesAccept({ name: 'a.txt', type: 'text/plain' }, undefined)).toBe(true)
  })
})
