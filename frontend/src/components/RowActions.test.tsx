import { fireEvent, render, screen } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import i18n from '../i18n'
import type { ProcessEntry } from '../meta/types'
import RowActions from './RowActions'

function process(name: string, properties: string[], values: string[]): ProcessEntry {
  return {
    name,
    version: 1,
    latest: true,
    deprecated: false,
    label: `Do ${name}`,
    description: '',
    input: { type: 'object', properties: Object.fromEntries(properties.map((p) => [p, { type: 'string' }])) },
    actsOn: { entity: 'Story', input: 'storyId', when: { field: 'status', values } },
    requiresMfa: false,
  } as ProcessEntry
}

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function renderActions(status: string) {
  const actions = [process('SUBMIT', ['storyId'], ['DRAFT']), process('REJECT', ['storyId', 'comment'], ['SUBMITTED'])]
  return render(
    <App>
      <MemoryRouter initialEntries={['/data/x']}>
        <Routes>
          <Route path="/data/x" element={<RowActions actions={actions} id="s-1" attributes={{ status }} onDone={() => {}} />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </App>,
  )
}

describe('RowActions', () => {
  it('offers the actions whose condition holds', async () => {
    await i18n.changeLanguage('en')
    renderActions('DRAFT')
    expect(screen.getByTestId('row-action-SUBMIT').textContent).toBe('Do SUBMIT')
    expect(screen.queryByTestId('row-action-REJECT')).toBeNull()
  })

  it('opens the form of a process that needs more than the key, with the key filled in', async () => {
    await i18n.changeLanguage('en')
    renderActions('SUBMITTED')
    expect(screen.queryByTestId('row-action-SUBMIT')).toBeNull()
    fireEvent.click(screen.getByTestId('row-action-REJECT'))
    expect(screen.getByTestId('where').textContent).toBe('/processes/REJECT/1?storyId=s-1')
  })

  it('asks before running a process that needs only the key', async () => {
    await i18n.changeLanguage('en')
    renderActions('DRAFT')
    fireEvent.click(screen.getByTestId('row-action-SUBMIT'))
    expect(await screen.findByText('Run “Do SUBMIT” on this entry?')).toBeTruthy()
  })
})
