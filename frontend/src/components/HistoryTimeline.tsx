import { Button, Space, Table, Tag, Timeline, Typography } from 'antd'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import { changesOf, isScheduled, newestFirst } from '../meta/history'
import { fieldLabel, findField, formatValue } from '../meta/kinds'
import type { DictItem, EntityMeta, HistoryVersion } from '../meta/types'

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

const ACTION_COLORS: Record<string, string> = {
  INSERT: 'green',
  UPDATE: 'blue',
  DELETE: 'red',
  REBASE: 'default',
  REVERT: 'orange',
  CANCEL: 'purple',
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
      items={newestFirst(versions).map((version) => {
        const changes = changesOf(versions, version)
        return {
          key: version.versionNo,
          color: version.deleted ? 'red' : isScheduled(version, now) ? 'gray' : 'blue',
          children: (
            <div data-testid={`version-${version.versionNo}`} data-action={version.action}>
              <Space wrap size={4}>
                <Typography.Text strong>{t('history.version', { no: version.versionNo })}</Typography.Text>
                <Tag color={ACTION_COLORS[version.action]}>{t(`history.action.${version.action}`)}</Tag>
                {isScheduled(version, now) && <Tag color="gold">{t('history.scheduled')}</Tag>}
                {version.deleted && <Tag color="red">{t('history.tombstone')}</Tag>}
              </Space>
              <div>
                <Typography.Text type="secondary">
                  {t('history.effective')} {time(version.effectStartTime)} · {t('history.recorded')}{' '}
                  {time(version.createdTime)} · {t('history.by')} {version.actorId} · {version.processName}
                  {version.reason ? ` · ${t('history.reason')}: ${version.reason}` : ''}
                </Typography.Text>
              </div>
              {changes.length > 0 && (
                <Table
                  size="small"
                  style={{ marginTop: 8, maxWidth: 640 }}
                  pagination={false}
                  rowKey="field"
                  dataSource={changes}
                  columns={[
                    { title: t('history.field'), dataIndex: 'field', render: (f: string) => label(f) },
                    { title: t('history.before'), dataIndex: 'before', render: (v: unknown, c) => show(c.field, v) },
                    { title: t('history.after'), dataIndex: 'after', render: (v: unknown, c) => show(c.field, v) },
                  ]}
                />
              )}
              <Space style={{ marginTop: 8 }}>
                <Button size="small" onClick={() => props.onViewAt(version)} data-testid="view-at">
                  {t('history.viewAt')}
                </Button>
                {props.canReadOperations && (
                  <Button size="small" onClick={() => props.onOperation(version.processSeqId)}>
                    {t('history.operation')} #{version.processSeqId}
                  </Button>
                )}
                {props.canRevert && version.action !== 'REBASE' && (
                  <Button size="small" danger onClick={() => props.onRevert(version.processSeqId)} data-testid="revert">
                    {t('history.revert')}
                  </Button>
                )}
              </Space>
            </div>
          ),
        }
      })}
    />
  )
}
