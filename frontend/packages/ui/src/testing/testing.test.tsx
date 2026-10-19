import { render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { expectAccessible, WCAG_TAGS } from '@jabiz/ui/testing'

/** The rules the check failed on: the violations it found, `rule: help (nodes)`. */
async function failedRules(check: Promise<unknown>): Promise<string[]> {
  const error = await check.then(() => null, (e: unknown) => e)
  expect(error, 'the check passed').not.toBeNull()
  return ((error as { actual?: string[] }).actual ?? []).map((found) => found.split(':')[0])
}

/** The appearances the document took while `run` ran, in order, from the class of the root element. */
async function appearancesDuring(run: () => Promise<unknown>): Promise<string[]> {
  const seen: string[] = []
  const observer = new MutationObserver(() => {
    seen.push(document.documentElement.classList.contains('dark') ? 'dark' : 'light')
  })
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  try {
    await run().catch(() => undefined)
  } finally {
    await new Promise((done) => setTimeout(done, 0))
    observer.disconnect()
  }
  return seen
}

afterEach(() => {
  document.documentElement.classList.remove('dark')
})

describe('@jabiz/ui/testing', () => {
  it('passes what has no violation, in both appearances, and leaves the light one', async () => {
    render(<main><h1>Title</h1><button type="button">Save</button></main>)
    const seen = await appearancesDuring(() => expectAccessible())
    expect(seen).toContain('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('fails on a violation, naming the rule and the appearance', async () => {
    render(<main><img src="data:," /><button type="button" /></main>)
    await expect(expectAccessible()).rejects.toThrow(/axe violations in the light appearance/)
    expect(await failedRules(expectAccessible())).toEqual(expect.arrayContaining(['image-alt', 'button-name']))
    // A failure does not leave the dark appearance behind for the next test.
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('checks only the given root when one is given', async () => {
    const { container } = render(<div><section data-testid="ok"><p>Fine</p></section><img src="data:," /></div>)
    await expectAccessible(container.querySelector('[data-testid="ok"]')!)
    expect(await failedRules(expectAccessible(container))).toEqual(['image-alt'])
  })

  it('checks WCAG 2.2 A and AA', () => {
    expect(WCAG_TAGS).toEqual(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
  })
})
