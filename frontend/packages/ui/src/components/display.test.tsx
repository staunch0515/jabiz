import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
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

  it('Spinner and PageState say what state the page is in', async () => {
    const { rerender } = render(<Spinner />)
    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
    rerender(<PageState kind="loading" />)
    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
    rerender(<PageState kind="notFound" title="PRICE_ADJUST@9" />)
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

  it('removes a tag with its button or Backspace in the empty field', async () => {
    render(<Field initial={['a', 'b', 'c']} />)
    await userEvent.click(screen.getByRole('button', { name: 'Remove b' }))
    expect(screen.getByRole('status')).toHaveTextContent('a|c')
    await userEvent.type(screen.getByLabelText('Tags'), '{Backspace}')
    expect(screen.getByRole('status')).toHaveTextContent(/^a$/)
  })
})
