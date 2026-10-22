import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '@jabiz/ui/testing'
import TasksPage from './TasksPage'

const TASKS = [
  { taskId: 't1', type: 'approval', title: 'Approve fin.journal JE-1 (level 1)', subjectId: 'r-1',
    subjectEntity: 'SysApprovalRequest', link: '/tasks', titleKey: 'task.approval', titleParams: {} },
  { taskId: 't2', type: 'fin.close', title: 'Close April', link: '/close', dueTime: '2026-05-01T00:00:00Z',
    titleKey: 'task.close', titleParams: {} },
]
const answer = vi.hoisted(() => ({ value: { isLoading: false, data: { total: 0, tasks: [] as unknown[] } } }))

vi.mock('../meta/hooks', () => ({ useMyTasks: () => answer.value }))
const permissions = vi.hoisted(() => ({ value: [] as string[] }))
vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  runProcess: vi.fn(),
  useAuth: () => ({ can: (permission: string) => permissions.value.includes(permission) }),
}))

function show() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <TasksPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('TasksPage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('lists the open tasks: approvals are decided in place, the others link to their page', async () => {
    answer.value = { isLoading: false, data: { total: 2, tasks: TASKS } }
    show()
    expect(screen.getByRole('heading', { level: 1, name: 'My tasks' })).toBeTruthy()
    expect(screen.getByText('Approve fin.journal JE-1 (level 1)')).toBeTruthy()
    expect(screen.getByTestId('approval-panel-r-1')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Close April' }).getAttribute('href')).toBe('/close')
    expect(screen.getByText(/^Due /)).toBeTruthy()
    // The approval's own title is not a link: it is decided here.
    expect(screen.queryByRole('link', { name: /Approve fin.journal/ })).toBeNull()
    await expectAccessible()
  })

  it('says when there is nothing to do', () => {
    answer.value = { isLoading: false, data: { total: 0, tasks: [] } }
    show()
    expect(screen.getByTestId('tasks-empty').textContent).toContain('Nothing to do.')
  })

  it('lets a holder of task.resolve close a task that is not an approval, with a note', async () => {
    const { runProcess } = await import('@jabiz/client')
    vi.mocked(runProcess).mockResolvedValue({ taskId: 't2', status: 'CANCELLED' })
    permissions.value = ['task.resolve']
    answer.value = { isLoading: false, data: { total: 2, tasks: TASKS } }
    show()
    // An approval is decided, never resolved by hand.
    expect(screen.queryByTestId('task-resolve-t1')).toBeNull()
    await userEvent.click(screen.getByTestId('task-resolve-t2'))
    const submit = screen.getByTestId('task-resolve-submit-t2')
    expect(submit).toBeDisabled()
    await userEvent.click(screen.getByLabelText('Not needed'))
    await userEvent.type(screen.getByTestId('task-resolve-note-t2'), ' Nothing to do ')
    await userEvent.click(submit)
    expect(runProcess).toHaveBeenCalledWith('TASK_RESOLVE', { taskId: 't2', outcome: 'CANCELLED', note: 'Nothing to do' })
    await expectAccessible()
  })

  it('offers nothing to resolve without the permission', () => {
    permissions.value = []
    answer.value = { isLoading: false, data: { total: 2, tasks: TASKS } }
    show()
    expect(screen.queryByTestId('task-resolve-t2')).toBeNull()
  })
})
