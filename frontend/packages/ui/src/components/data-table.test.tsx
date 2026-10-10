import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import { DataTable, type ColumnDef } from './data-table'

interface Carrier {
  code: string
  name: string
}

const columns: ColumnDef<Carrier, unknown>[] = [
  { accessorKey: 'code', header: 'Code' },
  { accessorKey: 'name', header: 'Name', enableSorting: false },
]

const rows: Carrier[] = [
  { code: 'A1', name: 'Alpha' },
  { code: 'B2', name: 'Beta' },
]

describe('DataTable', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('shows the rows with a named table, and is accessible', async () => {
    render(<DataTable label="Carriers" columns={columns} data={rows} getRowId={(r) => r.code} />)
    const table = screen.getByRole('table', { name: 'Carriers' })
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    expect(screen.getByRole('cell', { name: 'Alpha' })).toBeInTheDocument()
    await expectAccessible()
  })

  it('reports sorting to the caller, marks the sorted column, and leaves unsortable columns alone', async () => {
    const onSortingChange = vi.fn()
    const { rerender } = render(
      <DataTable label="Carriers" columns={columns} data={rows} sorting={[]} onSortingChange={onSortingChange} />,
    )
    expect(screen.getByRole('columnheader', { name: /Code/ })).toHaveAttribute('aria-sort', 'none')
    expect(screen.getByRole('columnheader', { name: 'Name' })).not.toHaveAttribute('aria-sort')
    expect(within(screen.getByRole('columnheader', { name: 'Name' })).queryByRole('button')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: /Code/ }))
    expect(onSortingChange).toHaveBeenCalledWith([{ id: 'code', desc: false }])

    rerender(
      <DataTable
        label="Carriers"
        columns={columns}
        data={rows}
        sorting={[{ id: 'code', desc: false }]}
        onSortingChange={onSortingChange}
      />,
    )
    expect(screen.getByRole('columnheader', { name: /Code/ })).toHaveAttribute('aria-sort', 'ascending')
    // The keyboard reaches the header's button and sorts with it.
    screen.getByRole('button', { name: /Code/ }).focus()
    await userEvent.keyboard('{Enter}')
    expect(onSortingChange).toHaveBeenLastCalledWith([{ id: 'code', desc: true }])
  })

  it('pages remotely: the caller gets the next page, the count and the page size', async () => {
    const onPaginationChange = vi.fn()
    render(
      <DataTable
        label="Carriers"
        columns={columns}
        data={rows}
        rowCount={45}
        pagination={{ pageIndex: 0, pageSize: 20 }}
        onPaginationChange={onPaginationChange}
      />,
    )
    expect(screen.getByText('45 items')).toBeInTheDocument()
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    expect(onPaginationChange).toHaveBeenCalledWith({ pageIndex: 1, pageSize: 20 })
    expect(screen.getByRole('combobox', { name: 'Rows per page' })).toBeInTheDocument()
    await expectAccessible()
  })

  it('pages without a count: Next while the page is full, or as the caller says', async () => {
    const onPaginationChange = vi.fn()
    const { rerender } = render(
      <DataTable label="Carriers" columns={columns} data={rows}
        pagination={{ pageIndex: 0, pageSize: 2 }} onPaginationChange={onPaginationChange} />,
    )
    expect(screen.getByText('Page 1')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    expect(onPaginationChange).toHaveBeenCalledWith({ pageIndex: 1, pageSize: 2 })

    rerender(
      <DataTable label="Carriers" columns={columns} data={rows.slice(0, 1)}
        pagination={{ pageIndex: 1, pageSize: 2 }} onPaginationChange={onPaginationChange} />,
    )
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeEnabled()

    rerender(
      <DataTable label="Carriers" columns={columns} data={rows} hasNextPage={false}
        pagination={{ pageIndex: 0, pageSize: 2 }} onPaginationChange={onPaginationChange} />,
    )
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
  })

  it('closes expanded rows when other rows arrive (another page or order)', async () => {
    const renderExpanded = (row: Carrier) => <p>Details of {row.name}</p>
    const { rerender } = render(
      <DataTable label="Carriers" columns={columns} data={rows} renderExpanded={renderExpanded} />,
    )
    await userEvent.click(screen.getAllByRole('button', { name: 'Expand row' })[0])
    expect(screen.getByText('Details of Alpha')).toBeInTheDocument()
    const next = [{ code: 'C3', name: 'Gamma' }, { code: 'D4', name: 'Delta' }]
    rerender(<DataTable label="Carriers" columns={columns} data={next} renderExpanded={renderExpanded} />)
    expect(screen.queryByText('Details of Gamma')).toBeNull()
    expect(screen.getAllByRole('button', { name: 'Expand row' })).toHaveLength(2)
  })

  it('expands rows with a named button that says whether the row is open', async () => {
    render(
      <DataTable
        label="Carriers"
        columns={columns}
        data={rows}
        getRowId={(r) => r.code}
        renderExpanded={(row) => <p>Details of {row.name}</p>}
      />,
    )
    const [first] = screen.getAllByRole('button', { name: 'Expand row' })
    expect(first).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(first)
    expect(screen.getByText('Details of Alpha')).toBeInTheDocument()
    const collapse = screen.getByRole('button', { name: 'Collapse row' })
    expect(collapse).toHaveAttribute('aria-expanded', 'true')
    await expectAccessible()
    await userEvent.click(collapse)
    expect(screen.queryByText('Details of Alpha')).toBeNull()
  })

  it('says when there is nothing, in the interface language', async () => {
    const { rerender } = render(<DataTable label="Carriers" columns={columns} data={[]} />)
    expect(screen.getByRole('cell', { name: 'No data' })).toHaveClass('text-muted-foreground')
    await act(() => i18n.changeLanguage('zh'))
    rerender(<DataTable label="承运人" columns={columns} data={[]} />)
    expect(screen.getByRole('cell', { name: '暂无数据' })).toBeInTheDocument()
    rerender(<DataTable label="Carriers" columns={columns} data={[]} empty="No carriers yet." />)
    expect(screen.getByRole('cell', { name: 'No carriers yet.' })).toBeInTheDocument()
    await expectAccessible()
  })

  it('marks data rows, and keeps a pinned column at the right edge with its own classes', async () => {
    const withActions: ColumnDef<Carrier, unknown>[] = [
      { accessorKey: 'code', header: 'Code', meta: { className: 'w-24' } },
      {
        id: 'actions',
        header: 'Actions',
        meta: { pinned: 'right' },
        cell: ({ row }) => <button type="button">Edit {row.original.code}</button>,
      },
    ]
    const { container } = render(
      <DataTable label="Carriers" columns={withActions} data={rows} rowProps={(r) => ({ 'data-code': r.code })} />,
    )
    const dataRows = container.querySelectorAll('[data-slot="data-table-row"]')
    expect([...dataRows].map((row) => row.getAttribute('data-code'))).toEqual(['A1', 'B2'])
    expect(screen.getByRole('columnheader', { name: 'Code' })).toHaveClass('w-24')
    expect(screen.getByRole('columnheader', { name: 'Actions' })).toHaveClass('sticky', 'right-0')
    expect(screen.getByRole('button', { name: 'Edit A1' }).closest('td')).toHaveClass('sticky', 'right-0')
    await expectAccessible()
  })

  it('makes a table that scrolls sideways a named region the keyboard reaches', async () => {
    const width = vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(900)
    const client = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(300)
    try {
      render(<DataTable label="Carriers" columns={columns} data={rows} />)
      const region = screen.getByRole('region', { name: 'Carriers' })
      expect(region).toHaveAttribute('tabindex', '0')
      await expectAccessible()
    } finally {
      width.mockRestore()
      client.mockRestore()
    }
    render(<DataTable label="Narrow" columns={columns} data={rows} />)
    expect(screen.queryByRole('region', { name: 'Narrow' })).toBeNull()
  })

  it('marks the table busy while loading', () => {
    render(<DataTable label="Carriers" columns={columns} data={[]} loading />)
    expect(screen.getByRole('table', { name: 'Carriers' })).toHaveAttribute('aria-busy', 'true')
  })
})
