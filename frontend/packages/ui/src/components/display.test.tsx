import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { CopyButton } from './copy-button'
import { DescriptionList } from './description-list'
import { PageState } from './page-state'
import { Spinner } from './spinner'
import { TagsInput } from './tags-input'
import { Timeline } from './timeline'

describe('display components', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('Timeline lists the events in order', async () => {
    render(
      <Timeline
        aria-label="Versions"
        data-testid="timeline"
        items={[
          { key: 2, tone: 'warning', content: <p>Version 2</p> },
          { key: 1, content: <p>Version 1</p> },
        ]}
      />,
    )
    const list = screen.getByRole('list', { name: 'Versions' })
    expect(within(list).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Version 2', 'Version 1'])
    await expectAccessible()
  })

  it('DescriptionList reads each value with its label', async () => {
    render(
      <DescriptionList
        data-testid="details"
        items={[
          { label: 'Process', value: 'jabiz.dataset.commit' },
          { key: 'by', label: <span>By</span>, value: 'Ann' },
        ]}
      />,
    )
    const terms = screen.getAllByRole('term')
    expect(terms.map((term) => term.textContent)).toEqual(['Process', 'By'])
    expect(screen.getAllByRole('definition')[0]).toHaveTextContent('jabiz.dataset.commit')
    await expectAccessible()
  })

  it('DescriptionList puts items side by side and copies a value', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    render(
      <DescriptionList
        columns={4}
        items={[
          { label: 'Records', value: 2 },
          { label: 'Rows', value: 1 },
          { label: 'SHA-256', value: 'abc123', copy: 'abc123' },
        ]}
      />,
    )
    const list = screen.getAllByRole('term')[0].closest('dl')!
    expect(list).toHaveAttribute('data-columns', '4')
    expect(list.className).toContain('lg:grid-cols-4')
    expect(screen.getAllByRole('definition')[2]).toHaveTextContent('abc123')
    await userEvent.click(screen.getByRole('button', { name: 'Copy SHA-256' }))
    expect(writeText).toHaveBeenCalledWith('abc123')
    await expectAccessible()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
  })

  it('Spinner and PageState say what state the page is in', async () => {
    const { rerender } = render(<Spinner />)
    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
    rerender(<PageState kind="loading" />)
    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
    expect(screen.getByRole('status').closest('[data-slot="page-state"]')).toHaveAttribute('data-state', 'loading')
    rerender(<PageState kind="empty" title="No reports." />)
    expect(screen.getByText('No reports.').closest('[data-slot="page-state"]')).toHaveAttribute('data-state', 'empty')
    expect(screen.queryByRole('alert')).toBeNull()
    rerender(<PageState kind="empty" />)
    expect(screen.getByText('No data')).toBeInTheDocument()
    rerender(<PageState kind="notFound" title="PRICE_ADJUST@9" />)
    expect(screen.getByText('PRICE_ADJUST@9').closest('[data-slot="page-state"]')).toHaveAttribute('data-state', 'notFound')
    expect(screen.getByText('PRICE_ADJUST@9')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    rerender(<PageState kind="error" description="The server is down." />)
    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong')
    expect(screen.getByRole('alert')).toHaveTextContent('The server is down.')
    rerender(
      <PageState kind="warning" title="Sign-in failed" description="Denied" action={<button type="button">Back</button>} />,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Sign-in failed')
    expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument()
    await expectAccessible()
  })
})

describe('CopyButton', () => {
  const writeText = vi.fn()
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    writeText.mockReset()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  })
  afterEach(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
  })

  it('copies the text and says so', async () => {
    writeText.mockResolvedValue(undefined)
    render(<CopyButton value="JBSWY3DPEE" label="Copy the key" />)
    await userEvent.click(screen.getByRole('button', { name: 'Copy the key' }))
    expect(writeText).toHaveBeenCalledWith('JBSWY3DPEE')
    expect(screen.getByRole('status')).toHaveTextContent('Copied')
    await expectAccessible()
  })

  it('says when it could not copy', async () => {
    writeText.mockRejectedValue(new Error('denied'))
    render(<CopyButton value="x" />)
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }))
    expect(screen.getByRole('status')).toHaveTextContent('Could not copy')
  })

  it('keeps its whole target beside a long text', () => {
    // jsdom lays nothing out: what keeps the 32px button whole in a flex row is that it does not shrink.
    render(<CopyButton value="x" className="ml-1" />)
    const slot = screen.getByRole('button', { name: 'Copy' }).closest('[data-slot="copy-button"]')
    expect(slot).toHaveClass('shrink-0', 'ml-1')
  })
})

describe('TagsInput', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  function Field({ initial = [] as string[] }) {
    const [value, setValue] = useState(initial)
    return (
      <>
        <label htmlFor="tags">Tags</label>
        <TagsInput id="tags" value={value} onChange={setValue} />
        <output>{value.join('|')}</output>
      </>
    )
  }

  it('adds tags on Enter, a comma or leaving the field, without duplicates', async () => {
    render(<Field />)
    const input = screen.getByLabelText('Tags')
    await userEvent.type(input, 'red{Enter}green,blue,red,')
    expect(screen.getByRole('status')).toHaveTextContent('red|green|blue')
    await userEvent.type(input, 'teal')
    await userEvent.tab()
    expect(screen.getByRole('status')).toHaveTextContent('red|green|blue|teal')
    await expectAccessible()
  })

  it('ignores Enter while an input method composes, and takes full-width and ideographic commas', async () => {
    render(<Field />)
    const input = screen.getByLabelText('Tags')
    fireEvent.change(input, { target: { value: 'とうきょう' } })
    // Enter confirming the composition (isComposing, or keyCode 229 in some browsers) adds nothing.
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 })
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    fireEvent.change(input, { target: { value: '東京' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('東京')
    await userEvent.type(input, '大阪，京都、奈良，')
    expect(screen.getByRole('status')).toHaveTextContent('東京|大阪|京都|奈良')
  })

  it('removes a tag with its button or Backspace in the empty field', async () => {
    render(<Field initial={['a', 'b', 'c']} />)
    await userEvent.click(screen.getByRole('button', { name: 'Remove b' }))
    expect(screen.getByRole('status')).toHaveTextContent('a|c')
    await userEvent.type(screen.getByLabelText('Tags'), '{Backspace}')
    expect(screen.getByRole('status')).toHaveTextContent(/^a$/)
  })
})
