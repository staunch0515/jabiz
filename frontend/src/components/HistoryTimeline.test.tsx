import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { version } from '../test/fixtures'
import type { EntityMeta } from '../meta/types'
import HistoryTimeline from './HistoryTimeline'

const entity = {
  entity: 'Carrier',
  label: 'Carrier',
  primaryKey: 'id',
  temporal: true,
  publishesChanges: false,
  fields: [
    { name: 'name', label: 'Name', type: 'text', multiline: false, immutable: false, required: false, generated: false, systemManaged: false, sensitive: false, processOnly: false, operators: [], rules: [] },
  ],
  references: [],
  listViews: [],
  dictionaries: [],
  unique: [],
  messages: {},
} as EntityMeta

describe('HistoryTimeline', () => {
  it('shows versions newest first with what changed, and offers the actions the user may take', async () => {
    await i18n.changeLanguage('en')
    const onViewAt = vi.fn()
    const onRevert = vi.fn()
    const versions = [
      version(1, { name: 'Alpha' }),
      version(2, { name: 'Bravo' }),
      version(3, { name: 'Charlie' }, { effectStartTime: '2099-01-01T00:00:00Z', reason: 'rename' }),
    ]
    render(
      <HistoryTimeline
        entity={entity}
        versions={versions}
        dictionaries={{}}
        now={new Date('2026-06-01T00:00:00Z')}
        canReadOperations={false}
        canRevert
        onViewAt={onViewAt}
        onOperation={() => {}}
        onRevert={onRevert}
      />,
    )
    const items = screen.getAllByTestId(/^version-/)
    expect(items.map((i) => i.dataset.testid)).toEqual(['version-3', 'version-2', 'version-1'])
    const latest = within(screen.getByTestId('version-3'))
    expect(latest.getByText('Scheduled')).toBeInTheDocument()
    expect(latest.getByText(/rename/)).toBeInTheDocument()
    expect(latest.getByText('Bravo')).toBeInTheDocument()
    expect(latest.getByText('Charlie')).toBeInTheDocument()
    expect(screen.queryByText(/Operation details/)).toBeNull()

    fireEvent.click(within(screen.getByTestId('version-2')).getByTestId('view-at'))
    expect(onViewAt).toHaveBeenCalledWith(versions[1])
    fireEvent.click(within(screen.getByTestId('version-1')).getByTestId('revert'))
    expect(onRevert).toHaveBeenCalledWith(11)
  })
})
