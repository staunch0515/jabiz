import { PlusOutlined } from '@ant-design/icons'
import { ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components'
import { App, Button, Card, Space } from 'antd'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { actionsFor } from '../meta/actions'
import { buildColumns, toRow, type Row } from '../meta/columns'
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

/**
 * The instances of another entity that refer to this one (docs/design/16-content-authoring.md section 4): a paged
 * list filtered by the reference, with the child's actions; new children get the reference filled in.
 */
export default function ChildEntityList({ child, parentId }: Props) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const dictionaries = useDictionaries(child.entity)
  const processes = useProcesses()
  const actionRef = useRef<ActionType>(undefined)
  const [editing, setEditing] = useState<EntityInstance | null | undefined>(undefined)
  const view = listViewOf(child.entity, child.dataset.listView)
  const actions = useMemo(() => actionsFor(child.entity.entity, processes.data ?? []), [child.entity.entity, processes.data])

  const columns = useMemo<ProColumns<Row>[]>(() => {
    const generated = buildColumns(child.entity, view, dictionaries, t, i18n.language)
      .filter((c) => !c.hideInTable && c.dataIndex !== child.field)
      .map((c) => ({ ...c, hideInSearch: true, sorter: false }))
    return [
      ...generated,
      {
        title: t('list.actions'),
        key: '__actions',
        valueType: 'option',
        render: (_, row) => (
          <Space size="small">
            <a onClick={() => setEditing(row.__instance)} data-testid="child-open">
              {t(child.dataset.canWrite ? 'list.edit' : 'list.view')}
            </a>
            <RowActions
              actions={actions}
              id={row.__instance.id}
              attributes={row}
              onDone={() => actionRef.current?.reload()}
            />
          </Space>
        ),
      },
    ]
  }, [child, view, dictionaries, t, i18n.language, actions])

  return (
    <Card
      size="small"
      title={child.entity.label}
      style={{ marginTop: 16 }}
      data-testid={`child-list-${child.entity.entity}`}
      extra={
        child.dataset.canWrite && (
          <Button size="small" icon={<PlusOutlined />} onClick={() => setEditing(null)} data-testid="child-create">
            {t('list.create')}
          </Button>
        )
      }
    >
      <ProTable<Row>
        rowKey="__key"
        actionRef={actionRef}
        columns={columns}
        search={false}
        options={false}
        size="small"
        pagination={{ defaultPageSize: 5 }}
        params={{ parentId }}
        request={async ({ current = 1, pageSize = 5 }) => {
          try {
            const result = await unwrap(
              api.POST('/api/datasets/{resourceId}/query', {
                params: { path: { resourceId: child.dataset.id } },
                body: {
                  filters: [{ field: child.field, op: 'eq', value: parentId }],
                  offset: (current - 1) * pageSize,
                  limit: pageSize,
                },
              }),
            )
            return { data: (result.items ?? []).map(toRow), total: result.total ?? 0, success: true }
          } catch (e) {
            message.error(e instanceof ApiError ? e.display : String(e))
            return { data: [], total: 0, success: false }
          }
        }}
      />
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
            actionRef.current?.reload()
          }}
        />
      )}
    </Card>
  )
}
