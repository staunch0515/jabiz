import { act, fireEvent, render, screen } from '@testing-library/react'
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

  it('as a dropzone takes a dropped file, and is still a real input for the keyboard and file choosers', async () => {
    const onUpload = vi.fn()
    render(<FileUpload variant="dropzone" onUpload={onUpload} accept=".csv" hint=".csv" data-testid="drop" />)
    const input = screen.getByLabelText(/Click or drop a file here/)
    expect(input).toHaveAttribute('type', 'file')
    expect(input).toBe(screen.getByTestId('drop'))
    // The accepted types describe the field without being part of its name.
    expect(input).toHaveAccessibleName('Click or drop a file here')
    expect(input).toHaveAccessibleDescription('.csv')
    await expectAccessible()

    const zone = input.closest('[data-slot="file-upload"]')!.querySelector('label')!
    const rows = new File(['a,b'], 'rows.csv', { type: 'text/csv' })
    fireEvent.dragOver(zone, { dataTransfer: { files: [rows] } })
    expect(zone).toHaveAttribute('data-dragging')
    fireEvent.drop(zone, { dataTransfer: { files: [rows] } })
    expect(zone).not.toHaveAttribute('data-dragging')
    await vi.waitFor(() => expect(onUpload).toHaveBeenCalledWith(rows))
    await vi.waitFor(() => expect(input).toBeEnabled())

    // A dropped file is checked like a chosen one.
    fireEvent.drop(zone, { dataTransfer: { files: [png()] } })
    expect(await screen.findByRole('alert')).toHaveTextContent('Files of this type are not accepted (.csv).')
    expect(onUpload).toHaveBeenCalledTimes(1)

    // Chosen through the input (keyboard, Playwright's setInputFiles), as the button variant.
    await userEvent.upload(input, new File(['c'], 'more.csv', { type: 'text/csv' }))
    expect(onUpload).toHaveBeenCalledTimes(2)
  })

  it('as a dropzone shows its own text and ignores drops while disabled', () => {
    const onUpload = vi.fn()
    render(<FileUpload variant="dropzone" onUpload={onUpload} label="stock.csv" disabled />)
    const input = screen.getByLabelText('stock.csv')
    expect(input).toBeDisabled()
    fireEvent.drop(input.closest('[data-slot="file-upload"]')!.querySelector('label')!, {
      dataTransfer: { files: [png()] },
    })
    expect(onUpload).not.toHaveBeenCalled()
  })

  it('matches types by media type, wildcard and extension', () => {
    expect(fileMatchesAccept({ name: 'a.PNG', type: 'image/png' }, 'image/*')).toBe(true)
    expect(fileMatchesAccept({ name: 'a.xlsx', type: '' }, '.xlsx')).toBe(true)
    expect(fileMatchesAccept({ name: 'a.txt', type: 'text/plain' }, 'application/pdf, .csv')).toBe(false)
    expect(fileMatchesAccept({ name: 'a.txt', type: 'text/plain' }, undefined)).toBe(true)
  })
})
