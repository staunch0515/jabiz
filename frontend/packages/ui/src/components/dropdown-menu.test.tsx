import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

/** Ends the closing animation (jsdom's own AnimationEvent carries no animationName, which Radix compares). */
function endAnimation(element: HTMLElement) {
  const event = new Event('animationend', { bubbles: true })
  Object.defineProperty(event, 'animationName', { value: 'exit' })
  act(() => {
    element.dispatchEvent(event)
  })
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

  describe('while the closed menu still animates out', () => {
    // jsdom runs no animations; with an animation name that changes on closing, Radix keeps the closed content
    // mounted until its animationend, as a browser does while the menu fades out.
    beforeEach(() => {
      const computed = globalThis.getComputedStyle.bind(globalThis)
      vi.spyOn(globalThis, 'getComputedStyle').mockImplementation((element, pseudo) => {
        const style = computed(element, pseudo)
        if (!element.matches('[data-slot="dropdown-menu-content"]')) return style
        // Live, as a browser's computed style is: Radix keeps the object and reads it again on closing.
        return new Proxy(style, {
          get: (target, key) =>
            key === 'animationName'
              ? element.getAttribute('data-state') === 'closed' ? 'exit' : 'enter'
              : Reflect.get(target, key, target),
        })
      })
    })
    afterEach(() => vi.restoreAllMocks())

    // The closing content still listens for presses outside it, and a press on the trigger is one: the trigger
    // opened the menu, the closing content dismissed it in the same event, and the press did nothing. Just after
    // switching the language the page re-renders and the menu takes long to fade out (the flaky
    // e2e/login.spec.ts language test, where the next item found was the one fading out).
    it.each([
      ['uncontrolled', false],
      ['controlled', true],
    ])('opens again when its trigger is pressed (%s)', async (_, controlled) => {
      function Controlled() {
        const [open, setOpen] = useState(false)
        return <LanguageMenu open={open} onOpenChange={setOpen} />
      }
      render(controlled ? <Controlled /> : <LanguageMenu />)
      const trigger = screen.getByRole('button', { name: 'Language' })
      press(trigger)
      const menu = await screen.findByRole('menu')

      act(() => {
        screen.getByRole('menuitemradio', { name: 'English' }).click()
      })
      // Closed, but still there while it animates out.
      expect(menu).toHaveAttribute('data-state', 'closed')
      expect(menu).toBeInTheDocument()

      press(trigger)
      endAnimation(menu)
      await settle()

      expect(trigger).toHaveAttribute('aria-expanded', 'true')
      expect(screen.getByRole('menu')).toHaveAttribute('data-state', 'open')
      expect(screen.getByRole('menuitemradio', { name: 'English' })).toBeVisible()
    })

    it('still closes when its trigger is pressed while open', async () => {
      render(<LanguageMenu />)
      const trigger = screen.getByRole('button', { name: 'Language' })
      press(trigger)
      const menu = await screen.findByRole('menu')
      press(trigger)
      expect(trigger).toHaveAttribute('aria-expanded', 'false')
      endAnimation(menu)
      await settle()
      expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    })

    it('still closes on a press outside it', async () => {
      render(<LanguageMenu />)
      press(screen.getByRole('button', { name: 'Language' }))
      const menu = await screen.findByRole('menu')
      await settle() // Radix listens for outside presses from the next task on
      press(screen.getByRole('button', { name: 'Elsewhere' }))
      expect(menu).toHaveAttribute('data-state', 'closed')
      endAnimation(menu)
      await settle()
      expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    })
  })
})
