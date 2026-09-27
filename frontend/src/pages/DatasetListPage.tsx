import { HistoryOutlined, PlusOutlined } from '@ant-design/icons'
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { Alert, App, Button, DatePicker, Popconfirm, Result, Space, Spin } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import EntityFormDrawer from '../components/EntityFormDrawer'
import RowActions from '../components/RowActions'
import { actionsFor } from '../meta/actions'
import { buildColumns, toRow, type Row } from '../meta/columns'
import { useDataset, useDatasets, useDictionaries, useEntityMeta, useProcesses } from '../meta/hooks'
import { buildFilters, buildSorts, listViewOf } from '../meta/listQuery'
import { useEntityMetas, useReferenceLabels } from '../meta/references'
import type { EntityInstance } from '../meta/types'
import { paths } from './paths'

/**
 * The generated list page of any dataset (docs/design/12-frontend.md section 5): columns, search and sorting from
 * the list view, create / edit / delete through the dataset API when the user may write, the processes acting on the
 * entity as row actions, references shown by their labels, and — for temporal entities — the data at another point
 * in time and the history of each entry.
 */
export default function DatasetListPage() {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const { datasetId = '' } = useParams()
  const dataset = useDataset(datasetId)
  const entity = useEntityMeta(dataset.data?.entity)
  const dictionaries = useDictionaries(entity.data)
  const [searchParams, setSearchParams] = useSearchParams()
  const actionRef = useRef<ActionType>(undefined)
  const [editing, setEditing] = useState<EntityInstance | null | undefined>(undefined)
  const [rows, setRows] = useState<Row[]>([])
  const datasets = useDatasets()
  const processes = useProcesses()
  const targets = useEntityMetas(
    (entity.data?.fields ?? []).flatMap((f) => (f.type === 'reference' ? [f.targetEntity] : [])),
  )
  const labels = useReferenceLabels(entity.data, rows, datasets.data ?? [], targets)
  const actions = useMemo(
    () => (entity.data ? actionsFor(entity.data.entity, processes.data ?? []) : []),
    [entity.data, processes.data],
  )

  const asOf = searchParams.get('asOf')
  const knownAt = searchParams.get('knownAt')
  const timeTravel = !!(asOf || knownAt)

  const view = entity.data ? listViewOf(entity.data, dataset.data?.listView) : undefined
  const canWrite = !!dataset.data?.canWrite && !timeTravel
  const showHistory = !!dataset.data?.temporal && !!dataset.data?.allowTimeTravel

  const columns = useMemo<ProColumns<Row>[]>(() => {
    if (!entity.data) return []
    const generated = buildColumns(entity.data, view, dictionaries, t, i18n.language, {
      labels,
      defaultLocale: entity.data.defaultLocale,
    })
    return [
      ...generated,
      {
        title: t('list.actions'),
        key: '__actions',
        valueType: 'option',
        fixed: 'right',
        render: (_, row) => (
          <Space size="small">
            <a onClick={() => setEditing(row.__instance)} data-testid={canWrite ? 'row-edit' : 'row-view'}>
              {t(canWrite ? 'list.edit' : 'list.view')}
            </a>
            {!timeTravel && (
              <RowActions
                actions={actions}
                id={row.__instance.id}
                attributes={row}
                onDone={() => actionRef.current?.reload()}
              />
            )}
            {canWrite && (
              <Popconfirm
                title={t('list.deleteConfirm')}
                onConfirm={async () => {
                  try {
                    await unwrap(
                      api.POST('/api/datasets/{resourceId}/commit', {
                        params: { path: { resourceId: datasetId } },
                        body: {
                          changes: [{ action: 'DELETE', id: row.__instance.id, version: row.__instance.version }],
                        },
                      }),
                    )
                    message.success(t('list.deleted'))
                    actionRef.current?.reload()
                  } catch (e) {
                    message.error(e instanceof ApiError ? e.display : String(e))
                  }
                }}
              >
                <a data-testid="row-delete">{t('list.delete')}</a>
              </Popconfirm>
            )}
            {showHistory && (
              <Link to={paths.history(datasetId, String(row.__instance.id))} data-testid="row-history">
                <HistoryOutlined /> {t('list.history')}
              </Link>
            )}
          </Space>
        ),
      },
    ]
  }, [entity.data, view, dictionaries, t, i18n.language, canWrite, showHistory, datasetId, message, labels, actions, timeTravel])

  if (dataset.isLoading || (dataset.data && entity.isLoading)) return <Spin style={{ margin: 48 }} />
  if (!dataset.data) return <Result status="404" title={datasetId} />
  if (entity.error || !entity.data) {
    return <Result status="error" title={entity.error instanceof ApiError ? entity.error.display : t('app.error')} />
  }

  const setTime = (key: 'asOf' | 'knownAt', value: Dayjs | null) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value.toISOString())
    else next.delete(key)
    setSearchParams(next, { replace: true })
  }

  return (
    <PageContainer title={<span data-testid="page-title">{entity.data.label}</span>} subTitle={dataset.data.id}>
      {timeTravel && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('list.timeTravelHint', { time: dayjs(asOf ?? knownAt).format('YYYY-MM-DD HH:mm:ss') })}
        />
      )}
      <ProTable<Row>
        rowKey="__key"
        actionRef={actionRef}
        columns={columns}
        params={{ asOf, knownAt, datasetId }}
        onLoad={(loaded) => setRows(loaded)}
        dateFormatter={(value) => value.toISOString()}
        scroll={{ x: 'max-content' }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        search={view?.filters?.length ? { labelWidth: 'auto', defaultCollapsed: false } : false}
        request={async (params, sort) => {
          const { current = 1, pageSize = 20, ...values } = params
          const body = {
            filters: buildFilters(entity.data!, view, values),
            sorts: buildSorts(view, sort as Record<string, string>),
            offset: (current - 1) * pageSize,
            limit: pageSize,
            asOf: asOf ?? undefined,
            knownAt: knownAt ?? undefined,
          }
          try {
            const result = await unwrap(
              api.POST('/api/datasets/{resourceId}/query', { params: { path: { resourceId: datasetId } }, body }),
            )
            return { data: (result.items ?? []).map(toRow), total: result.total ?? 0, success: true }
          } catch (e) {
            message.error(e instanceof ApiError ? e.display : String(e))
            return { data: [], total: 0, success: false }
          }
        }}
        toolBarRender={() => [
          ...(showHistory
            ? [
                <DatePicker
                  key="asOf"
                  showTime
                  allowClear
                  placeholder={t('list.asOf')}
                  value={asOf ? dayjs(asOf) : null}
                  onChange={(value) => setTime('asOf', value)}
                  data-testid="as-of"
                />,
                <DatePicker
                  key="knownAt"
                  showTime
                  allowClear
                  placeholder={t('list.knownAt')}
                  value={knownAt ? dayjs(knownAt) : null}
                  onChange={(value) => setTime('knownAt', value)}
                />,
              ]
            : []),
          ...(canWrite
            ? [
                <Button key="create" type="primary" icon={<PlusOutlined />} onClick={() => setEditing(null)} data-testid="create">
                  {t('list.create')}
                </Button>,
              ]
            : []),
        ]}
      />
      {editing !== undefined && (
        <EntityFormDrawer
          dataset={dataset.data}
          entity={entity.data}
          dictionaries={dictionaries}
          instance={editing ?? undefined}
          readOnly={!canWrite}
          historical={timeTravel}
          open
          onOpenChange={(open) => {
            if (!open) setEditing(undefined)
          }}
          onSaved={() => {
            setEditing(undefined)
            actionRef.current?.reload()
          }}
        />
      )}
    </PageContainer>
  )
}
