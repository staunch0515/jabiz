import {
  Badge,
  Button,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Timeline,
  type TimelineTone,
} from '@jabiz/ui'
import dayjs from 'dayjs'
import type { ComponentProps } from 'react'
import { useTranslation } from 'react-i18next'
import { changesOf, isScheduled, newestFirst } from '../meta/history'
import { fieldLabel, findField, formatValue } from '../meta/kinds'
import type { DictItem, EntityMeta, HistoryVersion } from '../meta/types'
import UserName from './UserName'

interface Props {
  entity: EntityMeta
  versions: HistoryVersion[]
  dictionaries: Record<string, DictItem[]>
  now: Date
  canReadOperations: boolean
  canRevert: boolean
  onViewAt(version: HistoryVersion): void
  onOperation(seq: number): void
  onRevert(seq: number): void
}

type BadgeVariant = ComponentProps<typeof Badge>['variant']

/** How each action is marked: the one place its colours are chosen (theme tokens through the badge's variants). */
const ACTION_BADGES: Record<HistoryVersion['action'], BadgeVariant> = {
  INSERT: 'success',
  UPDATE: 'default',
  DELETE: 'destructive',
  REBASE: 'outline',
  REVERT: 'warning',
  CANCEL: 'secondary',
}

/** The dot of a version on the timeline: deleted, scheduled, or in effect. */
function toneOf(version: HistoryVersion, now: Date): TimelineTone {
  return version.deleted ? 'destructive' : isScheduled(version, now) ? 'muted' : 'primary'
}

const time = (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss')

/**
 * Every version of a temporal entity, newest first: what changed (before → after), when it takes effect and when it
 * was recorded, by whom and why. Scheduled versions are marked; any version can be viewed as of its time.
 */
export default function HistoryTimeline(props: Props) {
  const { entity, versions, dictionaries, now } = props
  const { t, i18n } = useTranslation()
  const show = (field: string, value: unknown) => {
    const meta = findField(entity, field)
    return meta ? formatValue(meta, value, dictionaries, t, i18n.language) : String(value ?? '—')
  }
  const label = (field: string) => {
    const meta = findField(entity, field)
    return meta ? fieldLabel(meta, t) : field
  }

  return (
    <Timeline
      data-testid="history-timeline"
      aria-label={t('history.title', { entity: entity.label })}
      items={newestFirst(versions).map((version) => {
        const changes = changesOf(versions, version)
        const scheduled = isScheduled(version, now)
        return {
          key: version.versionNo,
          tone: toneOf(version, now),
          content: (
            <div data-testid={`version-${version.versionNo}`} data-action={version.action} className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="font-semibold">{t('history.version', { no: version.versionNo })}</span>
                <Badge variant={ACTION_BADGES[version.action]}>{t(`history.action.${version.action}`)}</Badge>
                {scheduled && <Badge variant="warning">{t('history.scheduled')}</Badge>}
                {version.deleted && <Badge variant="destructive">{t('history.tombstone')}</Badge>}
              </div>
              <p className="text-muted-foreground text-sm">
                {t('history.effective')} {time(version.effectStartTime)} · {t('history.recorded')}{' '}
                {time(version.createdTime)} · {t('history.by')} <UserName id={version.actorId} /> · {version.processName}
                {version.reason ? ` · ${t('history.reason')}: ${version.reason}` : ''}
              </p>
              {changes.length > 0 && (
                <div className="max-w-2xl rounded-md border">
                  <Table aria-label={t('history.changes', { no: version.versionNo })}>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{t('history.field')}</TableHead>
                        <TableHead>{t('history.before')}</TableHead>
                        <TableHead>{t('history.after')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {changes.map((change) => (
                        <TableRow key={change.field}>
                          <TableCell>{label(change.field)}</TableCell>
                          <TableCell className="whitespace-normal">{show(change.field, change.before)}</TableCell>
                          <TableCell className="whitespace-normal">{show(change.field, change.after)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" size="sm" onClick={() => props.onViewAt(version)} data-testid="view-at">
                  {t('history.viewAt')}
                </Button>
                {props.canReadOperations && (
                  <Button variant="outline" size="sm" onClick={() => props.onOperation(version.processSeqId)}>
                    {t('history.operation')} #{version.processSeqId}
                  </Button>
                )}
                {props.canRevert && version.action !== 'REBASE' && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="text-destructive"
                    onClick={() => props.onRevert(version.processSeqId)}
                    data-testid="revert"
                  >
                    {t('history.revert')}
                  </Button>
                )}
              </div>
            </div>
          ),
        }
      })}
    />
  )
}
