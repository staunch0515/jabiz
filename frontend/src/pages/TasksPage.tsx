import { PageContainer } from '@ant-design/pro-components'
import { useQueryClient } from '@tanstack/react-query'
import { Card, Empty, List, Space, Tag, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import ApprovalPanel from '../components/ApprovalPanel'
import { formatDateTime } from '../meta/format'
import { useMyTasks, type MyTask } from '../meta/hooks'

/**
 * The signed-in user's open tasks (docs/design/18-numbering-approvals-tasks.md section 5.3): assigned to them or to a
 * permission they hold. Approval tasks are decided here; the others link to where they are done.
 */
export default function TasksPage() {
  const { t } = useTranslation()
  const tasks = useMyTasks()
  const queries = useQueryClient()
  const refresh = () => void queries.invalidateQueries({ queryKey: ['tasks'] })

  const item = (task: MyTask) => (
    <List.Item key={task.taskId} data-testid={`task-${task.taskId}`}>
      <Card size="small" style={{ width: '100%' }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Space wrap>
            {task.link && task.type !== 'approval' ? (
              <Link to={task.link}><Typography.Text strong>{task.title}</Typography.Text></Link>
            ) : (
              <Typography.Text strong>{task.title}</Typography.Text>
            )}
            {task.dueTime && <Tag color="orange">{t('tasks.due', { time: formatDateTime(task.dueTime) })}</Tag>}
          </Space>
          {task.type === 'approval' && task.subjectId && (
            <ApprovalPanel requestId={task.subjectId} onDecided={refresh} />
          )}
        </Space>
      </Card>
    </List.Item>
  )

  return (
    <PageContainer title={t('tasks.title')}>
      <List
        loading={tasks.isLoading}
        dataSource={tasks.data?.tasks ?? []}
        renderItem={item}
        locale={{ emptyText: <Empty description={t('tasks.empty')} /> }}
      />
    </PageContainer>
  )
}
