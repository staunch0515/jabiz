import { act, render, screen, within } from '@testing-library/react'
import { i18n } from '@jabiz/client'
import { beforeEach, describe, expect, it } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { Steps } from './steps'

describe('Steps', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('lists the steps in order, the current one marked, the complete ones said in words', async () => {
    render(
      <Steps
        current={1}
        items={[{ title: 'File' }, { title: 'Columns', description: 'and parameters' }, { title: 'Preview' }]}
      />,
    )
    const list = screen.getByRole('list', { name: 'Progress' })
    const steps = within(list).getAllByRole('listitem')
    expect(steps.map((step) => step.getAttribute('data-state'))).toEqual(['complete', 'current', 'upcoming'])
    expect(steps[0]).toHaveTextContent('File (completed)')
    expect(steps[1]).toHaveAttribute('aria-current', 'step')
    expect(steps[1]).toHaveTextContent('and parameters')
    expect(steps[0]).not.toHaveAttribute('aria-current')
    expect(steps[2]).not.toHaveTextContent('completed')
    await expectAccessible()
  })

  it('takes its own name, in the language of the page', async () => {
    await act(() => i18n.changeLanguage('zh'))
    const { rerender } = render(<Steps current={0} items={[{ title: '文件' }]} />)
    expect(screen.getByRole('list', { name: '进度' })).toBeInTheDocument()
    rerender(<Steps current={0} items={[{ title: '文件' }]} aria-label="导入步骤" />)
    expect(screen.getByRole('list', { name: '导入步骤' })).toBeInTheDocument()
  })
})
