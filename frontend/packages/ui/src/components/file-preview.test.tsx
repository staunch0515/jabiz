import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { i18n } from '@jabiz/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import { FilePreview, type FilePreviewField } from './file-preview'

const photo: FilePreviewField = { image: true, variants: ['w160', 'w640'] }

const createObjectURL = vi.fn(() => 'blob:preview-1')
const revokeObjectURL = vi.fn()

beforeEach(async () => {
  await act(() => i18n.changeLanguage('en'))
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  createObjectURL.mockClear()
  revokeObjectURL.mockClear()
})

describe('FilePreview', () => {
  it('opens an image larger in a dialog and releases both object URLs', async () => {
    const paths: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      paths.push(path)
      return new Response('jpeg')
    }))
    const { unmount } = render(<FilePreview fileId="f-9" field={photo} width={48} />)
    await screen.findByAltText('Preview')
    expect(paths).toEqual(['/api/files/f-9/content/w160'])
    fireEvent.click(screen.getByRole('button', { name: 'Enlarge the preview' }))
    const dialog = await screen.findByRole('dialog', { name: 'Preview' })
    expect(await within(dialog).findByAltText('Preview')).toHaveAttribute('src', 'blob:preview-1')
    // No variant is that wide: the (re-encoded) original.
    expect(paths).toEqual(['/api/files/f-9/content/w160', '/api/files/f-9/content'])
    await expectAccessible()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(revokeObjectURL).toHaveBeenCalledTimes(1)
    unmount()
    expect(revokeObjectURL).toHaveBeenCalledTimes(2)
  })

  it('offers anything else as a download under the name the server gives', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response('%PDF', { headers: { 'Content-Disposition': "attachment; filename*=UTF-8''contract.pdf" } }),
    ))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(<FilePreview fileId="f-2" field={{ image: false }} width={48} />)
    expect(screen.queryByRole('img')).toBeNull()
    await expectAccessible()
    fireEvent.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
    expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('contract.pdf')
    click.mockRestore()
  })

  it('says so when the image cannot be loaded, in the interface language', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })))
    await act(() => i18n.changeLanguage('zh'))
    render(<FilePreview fileId="f-3" field={photo} width={48} />)
    expect(await screen.findByText('无法预览')).toBeInTheDocument()
  })
})
