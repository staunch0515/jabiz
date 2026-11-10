import { PageHeader } from '@jabiz/ui'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Plus } from 'lucide-react'
import { describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { mainAction, useHostedAdd, type HostedAdd } from './mainAction'

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

  it('hands a hosted section\'s Add up to the head and takes it back when the section goes', async () => {
    const opened = vi.fn()
    function Section({ onAdd }: { onAdd: (add: HostedAdd | null) => void }) {
      useHostedAdd(onAdd, opened, false)
      return <p>section</p>
    }
    function Host() {
      const [add, setAdd] = useState<HostedAdd | null>(null)
      const [shown, setShown] = useState(true)
      return (
        <>
          <PageHeader title="Notices" primaryAction={add ? mainAction({ label: 'New notice', icon: Plus,
            testId: 'notice-new', onClick: add.open }) : undefined} />
          {shown && <Section onAdd={setAdd} />}
          <button type="button" onClick={() => setShown(false)}>leave</button>
        </>
      )
    }
    render(<Host />)
    await userEvent.click(await screen.findByTestId('notice-new'))
    expect(opened).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByText('leave'))
    expect(screen.queryByTestId('notice-new')).toBeNull()
  })
})
