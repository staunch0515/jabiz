import { ApiError, formatDateTime, runProcess, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  Label,
  notify,
  PageState,
  RadioGroup,
  RadioGroupItem,
  Textarea,
} from '@jabiz/ui'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import ApprovalPanel from '../components/ApprovalPanel'
import AdminPage from '../layout/AdminPage'
import { useMyTasks, type MyTask } from '../meta/hooks'

/** The longest note of `TASK_RESOLVE` (`TaskResolution.NOTE_LENGTH`). */
const NOTE_LENGTH = 1000

/**
 * Closes a task by hand with `TASK_RESOLVE` (docs/design/18-numbering-approvals-tasks.md section 5.3a): done or not
 * needed, with a note. Whether the user may (they see it, it is not an approval's) is the server's to say.
 */
function ResolveForm({ taskId, titleId, onResolved }: { taskId: string; titleId: string; onResolved: () => void }) {
  const { t } = useTranslation()
  const id = useId()
  const [open, setOpen] = useState(false)
  const opener = useRef<HTMLButtonElement>(null)
  const returned = useRef(false)
  useEffect(() => {
    // Back from the form, focus goes to the button that opened it.
    if (!open && returned.current) opener.current?.focus()
  }, [open])
  const close = () => {
    returned.current = true
    setOpen(false)
  }
  const [outcome, setOutcome] = useState<'DONE' | 'CANCELLED'>('DONE')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (!open) {
    return (
      <Button ref={opener} variant="outline" size="sm" className="self-start" onClick={() => setOpen(true)}
        aria-describedby={titleId} data-testid={`task-resolve-${taskId}`}>
        {t('tasks.resolve')}
      </Button>
    )
  }
  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      await runProcess('TASK_RESOLVE', { taskId, outcome, note: note.trim() })
      notify.success(t('tasks.resolved'))
      onResolved()
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <fieldset className="flex flex-col gap-2" data-testid={`task-resolve-form-${taskId}`}>
      <legend className="mb-1 text-sm font-medium">{t('tasks.resolveTitle')}</legend>
      <p className="text-muted-foreground text-sm">{t('tasks.resolveHint')}</p>
      <RadioGroup value={outcome} onValueChange={(next) => setOutcome(next as 'DONE' | 'CANCELLED')}
        className="flex flex-wrap gap-4">
        {(['DONE', 'CANCELLED'] as const).map((value) => (
          <div key={value} className="flex items-center gap-2">
            <RadioGroupItem id={`${id}-${value}`} value={value} />
            <Label htmlFor={`${id}-${value}`}>{t(value === 'DONE' ? 'tasks.done' : 'tasks.cancelled')}</Label>
          </div>
        ))}
      </RadioGroup>
      <Label htmlFor={`${id}-note`}>{t('tasks.note')}</Label>
      <Textarea id={`${id}-note`} value={note} onChange={(e) => setNote(e.target.value)} maxLength={NOTE_LENGTH}
        rows={2} required data-testid={`task-resolve-note-${taskId}`} />
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap gap-2">
        <Button size="sm" disabled={busy || note.trim() === ''} aria-busy={busy || undefined} onClick={() => void submit()}
          data-testid={`task-resolve-submit-${taskId}`}>
          {t('tasks.resolve')}
        </Button>
        <Button size="sm" variant="outline" disabled={busy} onClick={close}>{t('tasks.cancel')}</Button>
      </div>
    </fieldset>
  )
}

/**
 * The signed-in user's open tasks (docs/design/18-numbering-approvals-tasks.md section 5.3): assigned to them or to a
 * permission they hold. Approval tasks are decided here; the others link to where they are done, and holders of
 * `task.resolve` close them by hand with a note (work no process will close).
 */
export default function TasksPage() {
  const { t } = useTranslation()
  const tasks = useMyTasks()
  const { can } = useAuth()
  const queries = useQueryClient()
  const refresh = () => void queries.invalidateQueries({ queryKey: ['tasks'] })
  const items = tasks.data?.tasks ?? []

  const item = (task: MyTask) => (
    <li key={task.taskId} data-testid={`task-${task.taskId}`}>
      <Card className="py-4">
        <CardContent className="flex flex-col gap-3 px-4">
          <div className="flex flex-wrap items-center gap-2">
            {task.link && task.type !== 'approval' ? (
              <Link id={`task-title-${task.taskId}`} to={task.link} className="text-primary font-medium hover:underline">
                {task.title}
              </Link>
            ) : (
              <span id={`task-title-${task.taskId}`} className="font-medium">{task.title}</span>
            )}
            {task.dueTime && (
              <Badge variant="warning">{t('tasks.due', { time: formatDateTime(task.dueTime) })}</Badge>
            )}
          </div>
          {task.type === 'approval' && task.subjectId && (
            <ApprovalPanel requestId={task.subjectId} onDecided={refresh} />
          )}
          {task.type !== 'approval' && task.taskId && can('task.resolve') && (
            <ResolveForm taskId={task.taskId} titleId={`task-title-${task.taskId}`} onResolved={refresh} />
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
