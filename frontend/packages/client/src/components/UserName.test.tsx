import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserName from './UserName'

const get = vi.fn()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => get(...args) },
  // As the real one: a failed call is thrown.
  unwrap: async (value: unknown) => {
    const answer = await value
    if (answer instanceof Error) throw answer
    return answer
  },
}))

const wrap = (node: React.ReactNode) => (
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>
)

describe('UserName', () => {
  beforeEach(() => {
    get.mockReset()
  })

  it('asks for the names a page shows in one request and shows the id of an actor that is no user', async () => {
    get.mockResolvedValue({ names: { 'u-1': 'Ann Lee', 'u-2': 'bob' } })
    render(wrap(<>
      <UserName id="u-1" /> <UserName id="u-2" /> <UserName id="u-1" /> <UserName id="system" /> <UserName id={null} />
    </>))
    expect(await screen.findAllByText('Ann Lee')).toHaveLength(2)
    expect(screen.getByText('bob')).toBeInTheDocument()
    expect(screen.getByText('system')).toBeInTheDocument()
    expect(get).toHaveBeenCalledTimes(1)
    expect(get.mock.calls[0][1].params.query.ids.sort()).toEqual(['system', 'u-1', 'u-2'])
  })

  it('asks for more names than the server answers at once in several requests', async () => {
    get.mockImplementation((...args: unknown[]) => {
      const ids = (args[1] as { params: { query: { ids: string[] } } }).params.query.ids
      return Promise.resolve({ names: Object.fromEntries(ids.map((id) => [id, `name ${id}`])) })
    })
    const ids = Array.from({ length: 250 }, (_, i) => `id-${i}`)
    render(wrap(<>{ids.map((id) => <UserName key={id} id={id} />)}</>))
    expect(await screen.findByText('name id-249')).toBeInTheDocument()
    expect(get).toHaveBeenCalledTimes(2)
    expect(get.mock.calls.map((c) => c[1].params.query.ids.length)).toEqual([200, 50])
  })

  it('shows the id when the names cannot be asked for', async () => {
    get.mockResolvedValue(new Error('offline'))
    render(wrap(<UserName id="u-9" />))
    await waitFor(() => expect(get).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('u-9')).toBeInTheDocument()
  })
})
