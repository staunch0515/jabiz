import { PageHeader } from '@jabiz/ui'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Plus } from 'lucide-react'
import { describe, expect, it, vi } from 'vitest'
import { mainAction } from './mainAction'

describe('a page main button as the head primary action (F-F3d)', () => {
  it('keeps the page own test id, label and click, and can be disabled', async () => {
    const onClick = vi.fn()
    const { rerender } = render(<PageHeader title="Quizzes"
      primaryAction={mainAction({ label: 'New quiz', icon: Plus, testId: 'new-quiz', onClick })} />)
    await userEvent.click(screen.getByTestId('new-quiz'))
    expect(onClick).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'New quiz' })).toBe(screen.getByTestId('new-quiz'))
    rerender(<PageHeader title="Quizzes"
      primaryAction={mainAction({ label: 'New quiz', icon: Plus, testId: 'new-quiz', onClick, disabled: true })} />)
    expect(screen.getByTestId('new-quiz')).toBeDisabled()
  })
})
