import type { PaginationState } from '@jabiz/ui'
import { useState } from 'react'

/**
 * Paging of a list held in full in the browser (the catalogs): the rows of the current page, and the DataTable's
 * paging props only when there is more than one page (no pager for a single page).
 */
export function useLocalPage<Row>(rows: Row[] | undefined, pageSize = 50) {
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize })
  const all = rows ?? []
  const pages = Math.max(1, Math.ceil(all.length / pagination.pageSize))
  // The list may have shrunk under the current page.
  const pageIndex = Math.min(pagination.pageIndex, pages - 1)
  const start = pageIndex * pagination.pageSize
  return {
    data: all.slice(start, start + pagination.pageSize),
    paging:
      all.length > pagination.pageSize
        ? {
            pagination: { ...pagination, pageIndex },
            onPaginationChange: setPagination,
            rowCount: all.length,
            pageSizeOptions: [pageSize],
          }
        : {},
  }
}
