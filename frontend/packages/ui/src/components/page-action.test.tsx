import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ScanLineIcon } from 'lucide-react'
import { describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { Fab } from './page-action'

/*
 * Phase 17zz31 (12 §13.1): a timeline's floating button, 20 px from the side and from the bottom menu, fading with the
 * bar as the page scrolls; without the new options the button is as it was (app-frame.test.tsx).
 */

describe('the floating button of a timeline', () => {
  it('keeps 20 px from the side and from the bottom menu', async () => {
    render(<Fab action={{ label: 'Scan', icon: ScanLineIcon, onClick: () => {} }} variant="timeline" />)
    const fab = screen.getByRole('button', { name: 'Scan' })
    expect(fab).toHaveAttribute('data-variant', 'timeline')
    expect(fab).toHaveClass('[--fab-inset:1.25rem]', 'right-(--fab-inset,1rem)', 'size-14')
    expect(fab.className).toContain('bottom-[calc(var(--bottom-bar-offset,0px)+var(--fab-inset,1rem)+env(safe-area-inset-bottom))]')
    await expectAccessible()
  })

  it('fades as a whole when told to, and can still be pressed', async () => {
    const press = vi.fn()
    render(<Fab action={{ label: 'Scan', icon: ScanLineIcon, onClick: press }} variant="timeline" dim={0.3} />)
    const fab = screen.getByRole('button', { name: 'Scan' })
    expect(fab).toHaveStyle({ opacity: '0.3' })
    expect(fab).toHaveClass('transition-opacity', 'duration-[170ms]', 'ease-linear')
    await userEvent.click(fab)
    expect(press).toHaveBeenCalledOnce()
  })

  it('fades a link too, and keeps the opacity between 0 and 1', () => {
    render(<Fab action={{ label: 'Scan', href: '/scan' }} dim={1.4} />)
    const fab = screen.getByRole('link', { name: 'Scan' })
    expect(fab).toHaveStyle({ opacity: '1' })
    expect(fab).toHaveAttribute('data-variant', 'default')
  })

  it('leaves the button as it was without the new options', () => {
    render(<Fab action={{ label: 'New', onClick: () => {} }} />)
    const fab = screen.getByRole('button', { name: 'New' })
    expect(fab).toHaveAttribute('data-variant', 'default')
    expect(fab).not.toHaveAttribute('style')
    expect(fab).not.toHaveClass('[--fab-inset:1.25rem]')
    expect(fab).not.toHaveClass('transition-opacity')
  })
})
