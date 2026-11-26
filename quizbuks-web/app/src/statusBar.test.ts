import { afterEach, describe, expect, it } from 'vitest'
import { followAppearanceInStatusBar, syncStatusBar } from './statusBar'

const meta = () => document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')

afterEach(() => {
  meta()?.remove()
  document.documentElement.classList.remove('dark')
  document.body.style.backgroundColor = ''
  document.getElementById('status-bar-style')?.remove()
})

describe('the status bar (Q113 P11)', () => {
  it('takes the page\'s background, once, in one meta element', () => {
    document.body.style.backgroundColor = 'rgb(255, 255, 255)'
    syncStatusBar()
    syncStatusBar()
    expect(document.querySelectorAll('meta[name="theme-color"]')).toHaveLength(1)
    expect(meta()?.content).toBe('rgb(255, 255, 255)')
  })

  it('follows a change of the appearance', async () => {
    const style = document.createElement('style')
    style.id = 'status-bar-style'
    style.textContent = 'body { background-color: rgb(255, 255, 255) } .dark body { background-color: rgb(10, 11, 11) }'
    document.head.appendChild(style)
    const stop = followAppearanceInStatusBar()
    expect(meta()?.content).toBe('rgb(255, 255, 255)')
    document.documentElement.classList.add('dark')
    await Promise.resolve()
    expect(meta()?.content).toBe('rgb(10, 11, 11)')
    stop()
  })

  it('writes nothing where the page has no colour yet', () => {
    syncStatusBar()
    expect(meta()).toBeNull()
  })
})
