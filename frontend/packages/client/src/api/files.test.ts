import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchFileContent, fileNameOf, formatBytes, previewVariant, uploadFile } from './files'
import { ApiError } from './problem'
import { session } from './session'

afterEach(() => {
  vi.unstubAllGlobals()
  session.clear()
})

describe('file API', () => {
  it('uploads one part named file with the session and returns the stored file', async () => {
    session.store('access-1', 'refresh-1')
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      expect(init.method).toBe('POST')
      expect(path).toBe('/api/files?policy=commerce.image')
      expect(new Headers(init.headers).get('Authorization')).toBe('Bearer access-1')
      const form = init.body as FormData
      expect([...form.keys()]).toEqual(['file'])
      expect((form.get('file') as File).name).toBe('shelf.jpg')
      return Response.json({ fileId: 'f-1', policy: 'commerce.image', contentType: 'image/jpeg', sizeBytes: 3, variants: [] }, { status: 201 })
    })
    vi.stubGlobal('fetch', fetch)
    const uploaded = await uploadFile('commerce.image', new Blob(['abc']), 'shelf.jpg')
    expect(uploaded.fileId).toBe('f-1')
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('reports refusals with the server violations', async () => {
    vi.stubGlobal('fetch', async () =>
      Response.json(
        { detail: 'refused', violations: [{ field: 'file', ruleCode: 'FILE_TYPE_NOT_ALLOWED', message: 'Not accepted.' }] },
        { status: 400 },
      ),
    )
    const error = await uploadFile('commerce.image', new Blob(['<html>']), 'x.jpg').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).violations.map((v) => v.ruleCode)).toEqual(['FILE_TYPE_NOT_ALLOWED'])
    expect((error as ApiError).display).toBe('Not accepted.')
  })

  it('reads contents and variants', async () => {
    const fetch = vi.fn(async (path: string) => new Response(path))
    vi.stubGlobal('fetch', fetch)
    expect(await (await fetchFileContent('f 1', 'w160')).text()).toBe('/api/files/f%201/content/w160')
    expect(await (await fetchFileContent('f1')).text()).toBe('/api/files/f1/content')
  })

  it('chooses the narrowest variant wide enough', () => {
    expect(previewVariant(['w1280', 'w160', 'w640'], 48)).toBe('w160')
    expect(previewVariant(['w160', 'w640'], 320)).toBe('w640')
    expect(previewVariant(['w160'], 320)).toBeUndefined()
    expect(previewVariant(undefined, 10)).toBeUndefined()
  })

  it('refreshes the session once on 401', async () => {
    session.store('old', 'refresh-1')
    const fetch = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/auth/refresh') return Response.json({ accessToken: 'new', refreshToken: 'refresh-2' })
      const token = new Headers(init?.headers).get('Authorization')
      return token === 'Bearer new' ? new Response('ok') : new Response(null, { status: 401 })
    })
    vi.stubGlobal('fetch', fetch)
    expect(await (await fetchFileContent('f1')).text()).toBe('ok')
    expect(session.accessToken()).toBe('new')
  })

  it('formats sizes and download names', () => {
    expect(formatBytes(10 * 1024 * 1024)).toBe('10 MB')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(12)).toBe('12 B')
    expect(fileNameOf(`attachment; filename="=?UTF-8?Q?x?="; filename*=UTF-8''%E5%A5%91%E7%B4%84.pdf`)).toBe('契約.pdf')
    expect(fileNameOf('inline; filename="a.jpg"')).toBe('a.jpg')
    expect(fileNameOf(null)).toBeUndefined()
  })
})
