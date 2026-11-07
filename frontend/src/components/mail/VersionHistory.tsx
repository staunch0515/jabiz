import { formatDateTime, UserName } from '@jabiz/client'
import { Badge, Button, DataTable, type ColumnDef } from '@jabiz/ui'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { HistoryVersion } from '../../meta/types'
import DiffView from './DiffView'

export interface VersionField {
  name: string
  label: string
}

export type Values = Record<string, string>

export interface VersionHistoryProps {
  versions: HistoryVersion[]
  loading: boolean
  error?: string | null
  fields: VersionField[]
  /** What is in effect now, which an opened version is compared with. */
  current: Values
  /** The values a version stands for (a return to the default stands for the default values). */
  valuesOf: (version: HistoryVersion) => Values
  /** Takes a version's values as the draft; absent: not offered (no right to write). */
  onUseAsDraft?: (values: Values) => void
}

/** How a version came to be, by the process that wrote it. */
function howKey(version: HistoryVersion): string {
  if (version.processName === 'CONTROL_CHANGE_PUBLISH') return 'mail.history.how.approved'
  if (version.processName?.endsWith('_RESET')) return 'mail.history.how.reset'
  if (version.processName === 'MAIL_TEXT_SAVE') return 'mail.history.how.saved'
  return 'mail.history.how.other'
}

/**
 * The versions of a mail text or layout, newest first (time, who as a name, kind and how), one of which can be
 * opened, compared line by line with what is in effect, and taken as the draft.
 */
export default function VersionHistory({
  versions, loading, error, fields, current, valuesOf, onUseAsDraft,
}: VersionHistoryProps) {
  const { t } = useTranslation()
  const [opened, setOpened] = useState<number | null>(null)
  const version = versions.find((v) => v.versionNo === opened)

  const columns: ColumnDef<HistoryVersion, unknown>[] = [
    { id: 'versionNo', header: t('mail.history.version'), cell: ({ row }) => row.original.versionNo, meta: { card: 'title' } },
    {
      id: 'time',
      header: t('mail.history.time'),
      cell: ({ row }) => formatDateTime(row.original.opTime ?? row.original.createdTime),
      meta: { card: 'subtitle' },
    },
    { id: 'who', header: t('mail.history.who'), cell: ({ row }) => <UserName id={row.original.actorId} /> },
    {
      id: 'kind',
      header: t('mail.history.kind'),
      cell: ({ row }) => {
        const kind = String(row.original.attributes?.kind ?? '')
        return (
          <Badge variant={kind === 'CUSTOM' ? 'secondary' : 'outline'}>
            {t(kind === 'CUSTOM' ? 'mail.history.kindCustom' : 'mail.history.kindDefault')}
          </Badge>
        )
      },
      meta: { card: 'badge' },
    },
    { id: 'how', header: t('mail.history.howTitle'), cell: ({ row }) => t(howKey(row.original)) },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('mail.history.open')}</span>,
      cell: ({ row }) => (
        <Button
          variant={opened === row.original.versionNo ? 'secondary' : 'outline'}
          size="sm"
          aria-pressed={opened === row.original.versionNo}
          onClick={() => setOpened(opened === row.original.versionNo ? null : row.original.versionNo)}
          data-testid={`mail-version-open-${row.original.versionNo}`}
        >
          {t('mail.history.openVersion', { version: row.original.versionNo })}
        </Button>
      ),
      meta: { card: 'actions' },
    },
  ]

  const values = version ? valuesOf(version) : undefined
  return (
    <section aria-labelledby="mail-history-title" className="flex flex-col gap-3">
      <h2 id="mail-history-title" className="text-base font-semibold">{t('mail.history.title')}</h2>
      <DataTable
        label={t('mail.history.title')}
        columns={columns}
        data={versions}
        getRowId={(v) => String(v.versionNo)}
        loading={loading}
        empty={error ?? t('mail.history.empty')}
      />
      {version && values && (
        <div className="flex flex-col gap-3 rounded-md border p-4" data-testid="mail-version-compare">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-sm font-semibold">{t('mail.history.compareTitle', { version: version.versionNo })}</h3>
            {onUseAsDraft && (
              <Button size="sm" onClick={() => onUseAsDraft(values)} data-testid="mail-version-use">
                {t('mail.history.useAsDraft')}
              </Button>
            )}
          </div>
          <p className="text-muted-foreground text-xs">{t('mail.history.compareHint')}</p>
          {fields.map((field) => (
            <DiffView
              key={field.name}
              label={field.label}
              older={values[field.name]}
              newer={current[field.name]}
              data-testid={`mail-diff-${field.name}`}
            />
          ))}
        </div>
      )}
    </section>
  )
}
