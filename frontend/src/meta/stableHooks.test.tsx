import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { carrier } from '../test/fixtures'
import { useDictionaries } from './hooks'
import { useReferenceLabels } from './references'
import type { DatasetEntry, EntityMeta, FieldMeta } from './types'

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: {
    GET: async () => [{ code: 'JP', label: 'Japan', sortOrder: 1, enabled: true }],
    POST: async () => ({ s1: 'Kanto Tea' }),
  },
  unwrap: async (value: unknown) => value,
}))

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>
}

/*
 * The generated tables build their columns from these hooks' results. A new object on every render would rebuild the
 * columns, and DataTable renders each cell function as a component: every cell would be mounted anew on any render,
 * losing focus and state (a value shown in plain, a preview).
 */
describe('metadata hooks', () => {
  it('give the same dictionaries object until the dictionaries change', async () => {
    const { result, rerender } = renderHook(() => useDictionaries(carrier), { wrapper })
    await waitFor(() => expect(result.current['urn:country']).toHaveLength(1))
    const first = result.current
    rerender()
    expect(result.current).toBe(first)
  })

  it('give the same reference labels object until the labels change', async () => {
    const entity = {
      ...carrier,
      fields: [{ name: 'supplierId', type: 'reference', targetEntity: 'Supplier', operators: [] } as unknown as FieldMeta],
    } as EntityMeta
    const datasets = [{ id: 'urn:s', entity: 'Supplier', isDefault: true } as DatasetEntry]
    const metas = { Supplier: { entity: 'Supplier', display: 'name' } as unknown as EntityMeta }
    const rows = [{ supplierId: 's1' }]
    const { result, rerender } = renderHook(() => useReferenceLabels(entity, rows, datasets, metas), { wrapper })
    await waitFor(() => expect(result.current.supplierId).toEqual({ s1: 'Kanto Tea' }))
    const first = result.current
    rerender()
    expect(result.current).toBe(first)
  })
})
