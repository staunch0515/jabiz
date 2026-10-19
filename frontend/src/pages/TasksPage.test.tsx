import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
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
vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  runProcess: vi.fn(),
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
})
