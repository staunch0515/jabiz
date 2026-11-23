import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const fetchFileContent = vi.fn(() => Promise.resolve(new Blob(['x'], { type: 'image/png' })))
vi.mock('@jabiz/client', async (importOriginal) => ({ ...await importOriginal<object>(),
  fetchFileContent: (...args: unknown[]) => fetchFileContent(...(args as [])) }))

const { FileImage } = await import('./FileImage')

afterEach(cleanup)

describe('FileImage', () => {
  it('paints an image shown again at once, from the one fetch (no empty box between)', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:cover')
    URL.revokeObjectURL = vi.fn()
    const first = render(<FileImage fileId="f1" variant="w640" alt="" data-testid="img" />)
    expect(await screen.findByTestId('img')).toHaveAttribute('src', 'blob:cover')
    first.unmount()
    // A card that starts its video shows its cover anew: the image is there from the first paint.
    render(<FileImage fileId="f1" variant="w640" alt="" data-testid="img" />)
    expect(screen.getByTestId('img')).toHaveAttribute('src', 'blob:cover')
    expect(fetchFileContent).toHaveBeenCalledTimes(1)
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
  })
})
