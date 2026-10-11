import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { Button } from './ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from './ui/dropdown-menu'

function LanguageMenu({ open, onOpenChange }: { open?: boolean; onOpenChange?: (open: boolean) => void }) {
  const [language, setLanguage] = useState('zh')
  return (
    <>
      <DropdownMenu open={open} onOpenChange={onOpenChange}>
        <DropdownMenuTrigger asChild>
          <Button>Language</Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent>
          <DropdownMenuRadioGroup value={language} onValueChange={setLanguage}>
            <DropdownMenuRadioItem value="zh">中文</DropdownMenuRadioItem>
            <DropdownMenuRadioItem value="en">English</DropdownMenuRadioItem>
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      <Button>Elsewhere</Button>
    </>
  )
}

/** Presses the trigger as a pointer does: the menu opens on pointerdown. */
function press(element: HTMLElement) {
  fireEvent.pointerDown(element, { button: 0, ctrlKey: false, pointerType: 'mouse' })
}

/** Runs the timers Radix defers work to (the focus restore after a menu leaves). */
async function settle() {
  await act(() => new Promise((resolve) => setTimeout(resolve, 20)))
}

describe('DropdownMenu', () => {
  it('returns focus to the trigger when it closes', async () => {
    render(<LanguageMenu />)
    const trigger = screen.getByRole('button', { name: 'Language' })
    press(trigger)
    expect(await screen.findByRole('menu')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('menuitemradio', { name: 'English' }))
    await settle()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('still closes when focus moves elsewhere (it is not modal)', async () => {
    render(<LanguageMenu />)
    press(screen.getByRole('button', { name: 'Language' }))
    expect(await screen.findByRole('menu')).toBeInTheDocument()
    act(() => screen.getByRole('button', { name: 'Elsewhere' }).focus())
    await settle()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  // The previous menu gives focus back to the trigger a task after it leaves the DOM; when the page is busy (as just
  // after switching the language, when every query is fetched again) that task runs late. A menu opened again in
  // between took that focus as focus moving out of it and closed at once (the flaky e2e/login.spec.ts language test).
  it.each([
    ['uncontrolled', false],
    ['controlled', true],
  ])('stays open when opened again before the previous one gave focus back (%s)', async (_, controlled) => {
    function Controlled() {
      const [open, setOpen] = useState(false)
      return <LanguageMenu open={open} onOpenChange={setOpen} />
    }
    render(controlled ? <Controlled /> : <LanguageMenu />)
    const trigger = screen.getByRole('button', { name: 'Language' })
    press(trigger)
    expect(await screen.findByRole('menu')).toBeInTheDocument()

    // Choosing closes the menu; open it again in the same task, before the deferred focus restore runs.
    act(() => {
      screen.getByRole('menuitemradio', { name: 'English' }).click()
    })
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    press(trigger)
    expect(screen.getByRole('menu')).toBeInTheDocument()

    await settle()
    expect(screen.getByRole('menu')).toBeInTheDocument()
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('menuitemradio', { name: 'English' })).toBeVisible()
  })
})
