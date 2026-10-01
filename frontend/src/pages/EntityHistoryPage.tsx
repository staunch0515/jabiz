import { PageContainer } from '@ant-design/pro-components'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Card, Col, DatePicker, Descriptions, Form, Input, Modal, Result, Row, Space, Spin, Tag } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import HistoryTimeline from '../components/HistoryTimeline'
import OperationDrawer from '../components/OperationDrawer'
import { differingFields } from '../meta/history'
import { useDataset, useDictionaries, useEntityMeta } from '../meta/hooks'
import { fieldLabel, formatValue } from '../meta/kinds'
import type { EntityInstance, HistoryVersion } from '../meta/types'
import { paths } from './paths'

/**
 * The history of one entry of a temporal entity (docs/design/04-temporal-append-only.md section 5): the timeline of
 * its versions, its state at any point in time (asOf, optionally as known at knownAt) compared with now, the
 * operations that wrote it, and reverting an operation when the user holds temporal.revert.
 */
export default function EntityHistoryPage() {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const { datasetId = '', entityId = '' } = useParams()
  const dataset = useDataset(datasetId)
  const entity = useEntityMeta(dataset.data?.entity)
  const dictionaries = useDictionaries(entity.data)
  const [searchParams, setSearchParams] = useSearchParams()
  const [operation, setOperation] = useState<number | null>(null)
  const [reverting, setReverting] = useState<number | null>(null)
  const [revertForm] = Form.useForm<{ reason: string }>()

  const asOf = searchParams.get('asOf')
  const knownAt = searchParams.get('knownAt')

  const history = useQuery({
    queryKey: ['history', datasetId, entityId],
    queryFn: async () =>
      (await unwrap(
        api.GET('/api/datasets/{resourceId}/entities/{id}/history', {
          params: { path: { resourceId: datasetId, id: entityId } },
        }),
      )) as unknown as HistoryVersion[],
  })

  const read = (at: string | null, known: string | null) =>
    unwrap(
      api.GET('/api/datasets/{resourceId}/entities/{id}', {
        params: {
          path: { resourceId: datasetId, id: entityId },
          query: { asOf: at ?? undefined, knownAt: known ?? undefined },
        },
      }),
    ).catch((e) => {
      // Not existing at that time (or deleted by now) is an answer, not an error.
      if (e instanceof ApiError && e.status === 404) return null
      throw e
    }) as Promise<EntityInstance | null>

  const current = useQuery({ queryKey: ['entity', datasetId, entityId, null, null], queryFn: () => read(null, null) })
  const pointInTime = useQuery({
    queryKey: ['entity', datasetId, entityId, asOf, knownAt],
    enabled: !!(asOf || knownAt),
    queryFn: () => read(asOf, knownAt),
  })

  if (dataset.isLoading || entity.isLoading || history.isLoading) return <Spin style={{ margin: 48 }} />
  if (!dataset.data || !entity.data) return <Result status="404" title={datasetId} />
  if (history.error) {
    return <Result status="error" title={history.error instanceof ApiError ? history.error.display : t('app.error')} />
  }

  const setPoint = (at: Dayjs | string | null, known: Dayjs | string | null) => {
    const next = new URLSearchParams(searchParams)
    const iso = (v: Dayjs | string | null) => (v == null ? null : typeof v === 'string' ? v : v.toISOString())
    const a = iso(at)
    const k = iso(known)
    if (a) next.set('asOf', a)
    else next.delete('asOf')
    if (k) next.set('knownAt', k)
    else next.delete('knownAt')
    setSearchParams(next, { replace: true })
  }

  const then = pointInTime.data?.attributes as Record<string, unknown> | undefined
  const now = current.data?.attributes as Record<string, unknown> | undefined
  const differing = differingFields(then, now)
  const visible = entity.data.fields.filter((f) => !f.sensitive)

  return (
    <PageContainer
      title={t('history.title', { entity: entity.data.label })}
      subTitle={entityId}
      extra={
        <Space>
          {/* Values before and after each change (docs/design/21-audit-retention.md section 1). */}
          {can('audit.read') && dataset.data?.entity && (
            <Link to={paths.audit(dataset.data.entity, entityId)} data-testid="history-audit">{t('audit.open')}</Link>
          )}
          <Link to={paths.dataset(datasetId)}>{t('history.back')}</Link>
        </Space>
      }
    >
      <Row gutter={24}>
        <Col xs={24} lg={13}>
          <Card>
            <HistoryTimeline
              entity={entity.data}
              versions={history.data ?? []}
              dictionaries={dictionaries}
              now={new Date()}
              canReadOperations={can('operation.read')}
              canRevert={can('temporal.revert') && !entity.data?.writeOnce}
              onViewAt={(version) => setPoint(version.effectStartTime, version.createdTime)}
              onOperation={setOperation}
              onRevert={(seq) => {
                revertForm.resetFields()
                setReverting(seq)
              }}
            />
          </Card>
        </Col>
        <Col xs={24} lg={11}>
          <Card title={t('history.viewPoint')}>
            <Space wrap style={{ marginBottom: 16 }}>
              <DatePicker
                showTime
                placeholder={t('history.pointInTime')}
                value={asOf ? dayjs(asOf) : null}
                onChange={(value) => setPoint(value, knownAt)}
                data-testid="point-as-of"
              />
              <DatePicker
                showTime
                placeholder={t('history.knownAt')}
                value={knownAt ? dayjs(knownAt) : null}
                onChange={(value) => setPoint(asOf, value)}
              />
            </Space>
            {(asOf || knownAt) && pointInTime.isLoading && <Spin />}
            {(asOf || knownAt) && pointInTime.error && (
              <Alert type="error" message={pointInTime.error instanceof ApiError ? pointInTime.error.display : String(pointInTime.error)} />
            )}
            {(asOf || knownAt) && pointInTime.isSuccess && !pointInTime.data && (
              <Alert type="info" message={t('history.notExisting')} data-testid="point-not-existing" />
            )}
            {pointInTime.data && (
              <Descriptions column={1} bordered size="small" data-testid="point-in-time">
                <Descriptions.Item label={t('fields.versionNo')}>{pointInTime.data.version}</Descriptions.Item>
                {visible.map((field) => (
                  <Descriptions.Item key={field.name} label={fieldLabel(field, t)}>
                    <span data-field={field.name}>
                      {formatValue(field, then?.[field.name], dictionaries, t, i18n.language)}
                    </span>
                    {current.data && differing.has(field.name) && !field.systemManaged && (
                      <Tag color="orange" style={{ marginLeft: 8 }}>
                        {t('history.differsFromNow')}: {formatValue(field, now?.[field.name], dictionaries, t, i18n.language)}
                      </Tag>
                    )}
                  </Descriptions.Item>
                ))}
              </Descriptions>
            )}
          </Card>
        </Col>
      </Row>
      <OperationDrawer seq={operation} onClose={() => setOperation(null)} />
      <Modal
        open={reverting != null}
        title={t('history.revertTitle', { seq: reverting })}
        okButtonProps={{ danger: true, id: 'revert-confirm' }}
        okText={t('history.revert')}
        onCancel={() => setReverting(null)}
        onOk={async () => {
          const { reason } = await revertForm.validateFields()
          try {
            await unwrap(
              api.POST('/api/processes/executions/{processSeqId}/revert', {
                params: { path: { processSeqId: reverting! } },
                body: { reason },
              }),
            )
            message.success(t('history.reverted'))
            setReverting(null)
            await queryClient.invalidateQueries({ queryKey: ['history', datasetId, entityId] })
            await queryClient.invalidateQueries({ queryKey: ['entity', datasetId, entityId] })
          } catch (e) {
            message.error(e instanceof ApiError ? e.display : String(e))
          }
        }}
      >
        <Form form={revertForm} layout="vertical">
          <Form.Item name="reason" label={t('history.revertReason')} rules={[{ required: true, whitespace: true }]}>
            <Input data-testid="revert-reason" />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  )
}
