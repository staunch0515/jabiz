import {
  Button,
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
  DataTable,
  notify,
  type ColumnDef,
  type PaginationState,
} from '@jabiz/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { actionsFor } from '../meta/actions'
import { toRow, type Row } from '../meta/cells'
import { dataColumns } from '../meta/dataColumns'
import { useDictionaries, useProcesses } from '../meta/hooks'
import { listViewOf } from '../meta/listQuery'
import type { ChildList } from '../meta/references'
import type { EntityInstance } from '../meta/types'
import EntityFormDrawer from './EntityFormDrawer'
import RowActions from './RowActions'

interface Props {
  child: ChildList
  parentId: string
}

const PAGE_SIZE = 5

/**
 * The instances of another entity that refer to this one (docs/design/16-content-authoring.md section 4): a paged
 * list filtered by the reference, with the child's actions; new children get the reference filled in. A child opens
 * in a sheet of its own over the parent's.
 */
export default function ChildEntityList({ child, parentId }: Props) {
  const { t, i18n } = useTranslation()
  const dictionaries = useDictionaries(child.entity)
  const processes = useProcesses()
  const [editing, setEditing] = useState<EntityInstance | null | undefined>(undefined)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const view = listViewOf(child.entity, child.dataset.listView)
  const actions = useMemo(() => actionsFor(child.entity.entity, processes.data ?? []), [child.entity.entity, processes.data])

  const rows = useQuery({
    queryKey: ['dataset-query', child.dataset.id, 'children', child.field, parentId, pagination],
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const result = await unwrap(
        api.POST('/api/datasets/{resourceId}/query', {
          params: { path: { resourceId: child.dataset.id } },
          body: {
            filters: [{ field: child.field, op: 'eq', value: parentId }],
            offset: pagination.pageIndex * pagination.pageSize,
            limit: pagination.pageSize,
          },
        }),
      )
      return { rows: (result.items ?? []).map(toRow), total: result.total ?? 0 }
    },
  })
  const { refetch } = rows
  useEffect(() => {
    if (rows.error) notify.error(rows.error instanceof ApiError ? rows.error.display : String(rows.error))
  }, [rows.error])

  const columns = useMemo<ColumnDef<Row, unknown>[]>(
    () => [
      // The reference to the parent is the same in every row.
      ...dataColumns(child.entity, view, dictionaries, t, i18n.language)
        .filter((c) => c.id !== child.field)
        .map((c) => ({ ...c, enableSorting: false })),
      {
        id: '__actions',
        header: t('list.actions'),
        meta: { pinned: 'right' },
        cell: ({ row }) => (
          <span className="flex flex-wrap items-center gap-1">
            <Button variant="link" size="sm" className="h-auto px-1" onClick={() => setEditing(row.original.__instance)} data-testid="child-open">
              {t(child.dataset.canWrite ? 'list.edit' : 'list.view')}
            </Button>
            <RowActions actions={actions} id={row.original.__instance.id} attributes={row.original} onDone={() => void refetch()} />
          </span>
        ),
      },
    ],
    [child, view, dictionaries, t, i18n.language, actions, refetch],
  )

  return (
    <Card className="mt-4 gap-3 py-4" data-testid={`child-list-${child.entity.entity}`}>
      <CardHeader className="px-4">
        <CardTitle>
          <h3 className="text-base">{child.entity.label}</h3>
        </CardTitle>
        {child.dataset.canWrite && (
          <CardAction>
            <Button size="sm" variant="outline" onClick={() => setEditing(null)} data-testid="child-create">
              <Plus aria-hidden />
              {t('list.create')}
            </Button>
          </CardAction>
        )}
      </CardHeader>
      <CardContent className="px-4">
        <DataTable
          label={child.entity.label}
          columns={columns}
          data={rows.data?.rows ?? []}
          getRowId={(row) => row.__key}
          loading={rows.isFetching}
          pagination={pagination}
          onPaginationChange={setPagination}
          rowCount={rows.data?.total}
          pageSizeOptions={[PAGE_SIZE]}
        />
      </CardContent>
      {editing !== undefined && (
        <EntityFormDrawer
          dataset={child.dataset}
          entity={child.entity}
          dictionaries={dictionaries}
          instance={editing ?? undefined}
          preset={editing ? undefined : { [child.field]: parentId }}
          readOnly={!child.dataset.canWrite}
          open
          onOpenChange={(open) => {
            if (!open) setEditing(undefined)
          }}
          onSaved={() => {
            setEditing(undefined)
            void rows.refetch()
          }}
        />
      )}
    </Card>
  )
}
