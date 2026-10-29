import { CopyButton, type ColumnDef } from '@jabiz/ui'
import type { TFunction } from 'i18next'
import type { Row } from './cells'

/**
 * The first column of a data list (17t): the row's key, shown as a button that copies it to the clipboard and shows it on hovering (the key
 * is long and of no use to read, but wanted to paste into a filter, a process form or a message). Not sortable.
 */
export function idColumn(t: TFunction): ColumnDef<Row, unknown> {
  return {
    id: '__id',
    header: t('list.id'),
    enableSorting: false,
    meta: { className: 'w-px' },
    cell: ({ row: { original: row } }) => (
      <CopyButton value={String(row.__instance.id)} label={t('list.copyId')} tooltip={String(row.__instance.id)}
        data-testid="row-copy-id" />
    ),
  }
}
