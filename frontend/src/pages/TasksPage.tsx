import { Badge, Card, CardContent, PageState } from '@jabiz/ui'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import ApprovalPanel from '../components/ApprovalPanel'
import AdminPage from '../layout/AdminPage'
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
  const items = tasks.data?.tasks ?? []

  const item = (task: MyTask) => (
    <li key={task.taskId} data-testid={`task-${task.taskId}`}>
      <Card className="py-4">
        <CardContent className="flex flex-col gap-3 px-4">
          <div className="flex flex-wrap items-center gap-2">
            {task.link && task.type !== 'approval' ? (
              <Link to={task.link} className="text-primary font-medium hover:underline">
                {task.title}
              </Link>
            ) : (
              <span className="font-medium">{task.title}</span>
            )}
            {task.dueTime && (
              <Badge variant="warning">{t('tasks.due', { time: formatDateTime(task.dueTime) })}</Badge>
            )}
          </div>
          {task.type === 'approval' && task.subjectId && (
            <ApprovalPanel requestId={task.subjectId} onDecided={refresh} />
          )}
        </CardContent>
      </Card>
    </li>
  )

  return (
    <AdminPage title={t('tasks.title')}>
      {tasks.isLoading ? (
        <PageState kind="loading" />
      ) : items.length === 0 ? (
        <PageState kind="empty" title={t('tasks.empty')} data-testid="tasks-empty" />
      ) : (
        <ul className="flex flex-col gap-3" data-testid="tasks">
          {items.map(item)}
        </ul>
      )}
    </AdminPage>
  )
}
