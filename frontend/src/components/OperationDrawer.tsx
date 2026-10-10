import {
  DataTable,
  DescriptionList,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  Spinner,
  type ColumnDef,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import UserName from './UserName'

interface Operation {
  processSeqId: number
  parentSeqId: number | null
  revertsSeqId: number | null
  processName: string
  processVersion: number
  actorId: string
  reason: string | null
  opTime: string
  items: Record<string, unknown>[]
  children: number[]
}

type Item = Record<string, unknown>

/**
 * An operation with the versions it wrote (GET /api/processes/executions/{seq}, permission operation.read), in a
 * sheet from the right.
 */
export default function OperationDrawer({ seq, onClose }: { seq: number | null; onClose(): void }) {
  const { t } = useTranslation()
  const operation = useQuery({
    queryKey: ['operation', seq],
    enabled: seq != null,
    queryFn: async () =>
      (await unwrap(
        api.GET('/api/processes/executions/{processSeqId}', { params: { path: { processSeqId: seq! } } }),
      )) as unknown as Operation,
  })
  const data = operation.data
  const itemKeys = data?.items?.length ? Object.keys(data.items[0]) : []
  const columns: ColumnDef<Item, unknown>[] = itemKeys.map((key) => ({
    id: key,
    header: key,
    cell: ({ row }) => {
      const value = row.original[key]
      return Array.isArray(value) ? value.join(', ') : String(value ?? '—')
    },
  }))
  const title = t('history.operation') + (seq ? ` #${seq}` : '')

  return (
    <Sheet open={seq != null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-2xl">
        <SheetHeader>
          <SheetTitle>{title}</SheetTitle>
          <SheetDescription className="sr-only">{data?.processName ?? title}</SheetDescription>
        </SheetHeader>
        <div className="flex flex-col gap-4 px-4 pb-6">
          {operation.isLoading && <Spinner />}
          {operation.error && (
            <p role="alert" className="text-destructive text-sm">
              {operation.error instanceof ApiError ? operation.error.display : String(operation.error)}
            </p>
          )}
          {data && (
            <>
              <DescriptionList
                data-testid="operation-detail"
                items={[
                  { key: 'seq', label: '#', value: data.processSeqId },
                  { key: 'process', label: t('history.process'), value: `${data.processName} v${data.processVersion}` },
                  { key: 'by', label: t('history.by'), value: <UserName id={data.actorId} /> },
                  { key: 'time', label: t('history.recorded'), value: dayjs(data.opTime).format('YYYY-MM-DD HH:mm:ss') },
                  { key: 'reason', label: t('history.reason'), value: data.reason ?? '—' },
                  ...(data.parentSeqId != null ? [{ key: 'parent', label: 'parent', value: `#${data.parentSeqId}` }] : []),
                  ...(data.revertsSeqId != null
                    ? [{ key: 'reverts', label: t('history.revert'), value: `#${data.revertsSeqId}` }]
                    : []),
                ]}
              />
              <h3 className="text-sm font-semibold">{t('history.items')}</h3>
              <DataTable label={t('history.items')} columns={columns} data={data.items ?? []} />
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}
