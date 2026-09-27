import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import type { FileFieldMeta } from '../meta/types'
import { FileInput } from './FileField'

const t = i18n.t.bind(i18n)

const photo: FileFieldMeta = {
  name: 'imageFileId',
  label: 'Photo',
  type: 'custom',
  kindId: 'jabiz.file',
  policy: 'commerce.image',
  accept: ['image/jpeg', 'image/png', '.jpg', '.jpeg', '.png'],
  maxBytes: 1024,
  image: true,
  variants: ['w160', 'w640'],
  immutable: false,
  required: false,
  generated: false,
  systemManaged: false,
  sensitive: false,
  operators: [],
  rules: [],
}

const createObjectURL = vi.fn(() => 'blob:preview-1')
const revokeObjectURL = vi.fn()

beforeEach(async () => {
  await i18n.changeLanguage('en')
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  createObjectURL.mockClear()
  revokeObjectURL.mockClear()
})

function fileInput(container: HTMLElement): HTMLInputElement {
  return container.querySelector('input[type=file]') as HTMLInputElement
}

describe('FileInput', () => {
  it('filters the picker by the policy and says what is accepted', () => {
    const { container } = render(<FileInput field={photo} disabled={false} t={t} />)
    expect(fileInput(container).accept).toBe('image/jpeg,image/png,.jpg,.jpeg,.png')
    expect(screen.getByText('Accepted: .jpg, .jpeg, .png, up to 1 KB')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Upload/ })).toBeInTheDocument()
  })

  it('refuses a file over the limit without uploading it', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const onChange = vi.fn()
    const { container } = render(<FileInput field={photo} disabled={false} t={t} onChange={onChange} />)
    fireEvent.change(fileInput(container), { target: { files: [new File(['x'.repeat(2048)], 'big.jpg', { type: 'image/jpeg' })] } })
    expect(await screen.findByRole('alert')).toHaveTextContent('The file is larger than 1 KB.')
    expect(fetch).not.toHaveBeenCalled()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('uploads and keeps the returned id', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      Response.json({ fileId: 'f-1', policy: 'commerce.image', contentType: 'image/jpeg', sizeBytes: 3, variants: [] }, { status: 201 }),
    ))
    const onChange = vi.fn()
    const { container } = render(<FileInput field={photo} disabled={false} t={t} onChange={onChange} />)
    fireEvent.change(fileInput(container), { target: { files: [new File(['abc'], 'a.jpg', { type: 'image/jpeg' })] } })
    await waitFor(() => expect(onChange).toHaveBeenCalledWith('f-1'))
  })

  it('shows the server refusal', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      Response.json({ violations: [{ field: 'file', ruleCode: 'FILE_TYPE_NOT_ALLOWED', message: 'This type of file is not accepted here.' }] }, { status: 400 }),
    ))
    const onChange = vi.fn()
    const { container } = render(<FileInput field={photo} disabled={false} t={t} onChange={onChange} />)
    fireEvent.change(fileInput(container), { target: { files: [new File(['<html>'], 'a.jpg', { type: 'image/jpeg' })] } })
    expect(await screen.findByRole('alert')).toHaveTextContent('This type of file is not accepted here.')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('previews the current image through an object URL, released when it goes', async () => {
    const fetch = vi.fn(async (path: string) => {
      expect(path).toBe('/api/files/f-9/content/w640')
      return new Response('jpeg')
    })
    vi.stubGlobal('fetch', fetch)
    const onChange = vi.fn()
    const { unmount } = render(<FileInput field={photo} disabled={false} t={t} value="f-9" onChange={onChange} />)
    const preview = await screen.findByAltText('Preview')
    expect(preview).toHaveAttribute('src', 'blob:preview-1')
    fireEvent.click(screen.getByRole('button', { name: /Remove/ }))
    expect(onChange).toHaveBeenCalledWith(null)
    unmount()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:preview-1')
  })

  it('offers documents as a download', () => {
    const contract: FileFieldMeta = { ...photo, name: 'contractFileId', policy: 'commerce.document', image: false, variants: [], accept: ['application/pdf', '.pdf'] }
    render(<FileInput field={contract} disabled t={t} value="f-2" />)
    expect(screen.getByRole('button', { name: /Download/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument()
  })
})
