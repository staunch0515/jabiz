import { act, render, screen } from '@testing-library/react'
import { i18n } from '@jabiz/client'
import { beforeEach, describe, expect, it } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { Button } from './ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './ui/alert-dialog'

const LONG = Array.from({ length: 60 }, (_, i) => `Line ${i + 1} of a long explanation.`)

function LongDialog({ className }: { className?: string }) {
  return (
    <Dialog open>
      <DialogContent className={className}>
        <DialogHeader>
          <DialogTitle>Long dialog</DialogTitle>
          <DialogDescription>Its content is taller than a small screen.</DialogDescription>
        </DialogHeader>
        {LONG.map((line) => <p key={line}>{line}</p>)}
        <DialogFooter>
          <Button>Confirm</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// jsdom lays nothing out, so these check the classes; the end-to-end test (e2e/dialog-height.spec.ts) checks that the
// button of a long dialog on a small screen can be reached.
describe('DialogContent and AlertDialogContent (ROADMAP 17zz19)', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('is at most the viewport high and scrolls what is longer', async () => {
    render(<LongDialog />)
    const dialog = screen.getByRole('dialog', { name: 'Long dialog' })
    expect(dialog).toHaveClass('max-h-[calc(100dvh-2rem)]', 'overflow-y-auto')
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument()
    await expectAccessible()
  })

  it('lets a caller replace the height and the overflow, as a dialog with a fixed footer does', () => {
    render(<LongDialog className="flex max-h-96 flex-col overflow-hidden" />)
    const dialog = screen.getByRole('dialog', { name: 'Long dialog' })
    expect(dialog).toHaveClass('max-h-96', 'overflow-hidden')
    expect(dialog).not.toHaveClass('max-h-[calc(100dvh-2rem)]')
    expect(dialog).not.toHaveClass('overflow-y-auto')
  })

  it('caps an alert dialog the same way', async () => {
    render(
      <AlertDialog open>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Long question</AlertDialogTitle>
            <AlertDialogDescription>{LONG.join(' ')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction>Confirm</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>,
    )
    const dialog = screen.getByRole('alertdialog', { name: 'Long question' })
    expect(dialog).toHaveClass('max-h-[calc(100dvh-2rem)]', 'overflow-y-auto')
    await expectAccessible()
  })
})
