import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import TasksPage from './TasksPage'

vi.mock('../meta/hooks', () => ({
  useMyTasks: () => ({
    isLoading: false,
    data: {
      total: 2,
      tasks: [
        { taskId: 't1', type: 'approval', title: 'Approve fin.journal JE-1 (level 1)', subjectId: 'r-1',
          subjectEntity: 'SysApprovalRequest', link: '/tasks', titleKey: 'task.approval', titleParams: {} },
        { taskId: 't2', type: 'fin.close', title: 'Close April', link: '/close', dueTime: '2026-05-01T00:00:00Z',
          titleKey: 'task.close', titleParams: {} },
      ],
    },
  }),
}))

vi.mock('../lib/calls', () => ({ runProcess: vi.fn() }))

describe('TasksPage', () => {
  it('lists the open tasks: approvals are decided in place, the others link to their page', async () => {
    await i18n.changeLanguage('en')
    render(
      <QueryClientProvider client={new QueryClient()}>
        <App>
          <MemoryRouter>
            <TasksPage />
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )
    expect(screen.getByText('Approve fin.journal JE-1 (level 1)')).toBeTruthy()
    expect(screen.getByTestId('approval-panel-r-1')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Close April' }).getAttribute('href')).toBe('/close')
    expect(screen.getByText(/^Due /)).toBeTruthy()
  })
})
