import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import { ConfirmDialog } from './confirm-dialog'
import { Button } from './ui/button'

describe('ConfirmDialog', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('asks as a named alert dialog, starts on Cancel, and Escape cancels', async () => {
    const onConfirm = vi.fn()
    render(
      <ConfirmDialog
        title="Delete the carrier?"
        description="Its prices stay."
        onConfirm={onConfirm}
        trigger={<Button>Delete</Button>}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }))
    const dialog = screen.getByRole('alertdialog', { name: 'Delete the carrier?' })
    expect(dialog).toHaveAccessibleDescription('Its prices stay.')
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus()
    await expectAccessible()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('runs the action and closes when it succeeds; stays open when it fails', async () => {
    let fail = true
    const onConfirm = vi.fn(async () => {
      if (fail) throw new Error('refused')
    })
    render(<ConfirmDialog title="Revoke?" confirmLabel="Revoke" destructive onConfirm={onConfirm} trigger={<Button>Open</Button>} />)
    await userEvent.click(screen.getByRole('button', { name: 'Open' }))
    await userEvent.click(screen.getByRole('button', { name: 'Revoke' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('alertdialog', { name: 'Revoke?' })).toBeInTheDocument()

    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Revoke' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  })

  it('can be controlled, and speaks the interface language', async () => {
    await act(() => i18n.changeLanguage('ja'))
    const onOpenChange = vi.fn()
    render(<ConfirmDialog title="削除しますか？" open onOpenChange={onOpenChange} onConfirm={() => {}} />)
    expect(screen.getByRole('button', { name: 'キャンセル' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'OK' }))
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })
})
