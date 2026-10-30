import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { matches, useLocalList } from './useLocalList'

type Row = { name: string; size: number | null; when: string | null }
const rows: Row[] = [
  { name: 'Écluse', size: 3, when: '2026-10-02T00:00:00Z' },
  { name: 'apple', size: null, when: null },
  { name: 'Banana split', size: 10, when: '2026-10-01T00:00:00Z' },
  { name: 'cherry', size: 2, when: '2026-10-03T00:00:00Z' },
]

describe('useLocalList (phase 17w)', () => {
  it('matches every word, ignoring case and accents', () => {
    expect(matches(['Banana split'], 'SPLIT banana')).toBe(true)
    expect(matches(['Écluse'], 'ecl')).toBe(true)
    expect(matches(['Banana split'], 'banana cherry')).toBe(false)
    expect(matches([null, undefined], '  ')).toBe(true)
  })

  it('searches, sorts with empty values last both ways, and pages', () => {
    const { result } = renderHook(() => useLocalList(rows, {
      text: (r) => [r.name],
      sortBy: { name: (r) => r.name, size: (r) => r.size, when: (r) => r.when },
      defaultSort: [{ id: 'name', desc: false }],
      pageSize: 2,
      locale: 'en',
    }))
    const names = () => result.current.table.data.map((r) => r.name)
    expect(names()).toEqual(['apple', 'Banana split'])
    expect(result.current.table.rowCount).toBe(4)
    act(() => result.current.table.onPaginationChange?.({ pageIndex: 1, pageSize: 2 }))
    expect(names()).toEqual(['cherry', 'Écluse'])
    act(() => result.current.table.onSortingChange([{ id: 'size', desc: false }]))
    // A new order starts at the first page; numbers by value, the empty one last.
    expect(names()).toEqual(['cherry', 'Écluse'])
    act(() => result.current.table.onPaginationChange?.({ pageIndex: 1, pageSize: 2 }))
    expect(names()).toEqual(['Banana split', 'apple'])
    act(() => result.current.table.onSortingChange([{ id: 'when', desc: true }]))
    act(() => result.current.table.onPaginationChange?.({ pageIndex: 1, pageSize: 2 }))
    expect(names()).toEqual(['Banana split', 'apple'])
    act(() => result.current.setQuery('an'))
    expect(result.current.matched).toBe(1)
    expect(result.current.total).toBe(4)
    expect(names()).toEqual(['Banana split'])
    // One page: no pager.
    expect(result.current.table.pagination).toBeUndefined()
  })
})
