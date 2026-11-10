import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import type { PublicationView } from '../play'
import { CONTENT_TABS, PublicationContent, type ContentTab, type ContentTabs } from './PublicationContent'
import './messages'

afterEach(cleanup)

const VIEW = {
  publicationId: 'p1', name: 'Coffee week', quizTitle: 'Coffee basics', cover: null, watchRequired: false,
  videoDurationSec: null, kind: 'QUIZ', intro: 'Two **easy** questions.', materials: [
    { no: 1, kind: 'ARTICLE', title: 'Bean guide', description: null, url: null, body: 'About beans', image: null },
  ], status: 'LIVE', startAt: '2026-11-01T00:00:00Z', endAt: '2026-11-08T00:00:00Z', timeLimitSec: 300,
  questionCount: 2, pickCount: null, fullScore: 2, passScore: 2, maxReward: '100', rewardMethod: 'OFFLINE_REDEEM',
  redeemDescription: 'A free espresso', digitalDelivery: null, digitalDescription: null, sponsorName: 'Acme',
  sponsorCompany: null, sponsorIntro: 'We roast coffee.', sponsorAvatar: null, sponsorCountry: null,
  completions: 5, last1h: 0, last24h: 1, last7d: 2, last30d: 4, statsAt: null,
} as unknown as PublicationView

/** Tabs as buttons held in state (the way the sponsor's preview draws them), with an optional extra under a tab. */
function Tabbed({ view = VIEW, draft = false, extra }: {
  view?: PublicationView
  draft?: boolean
  extra?: ContentTabs['extra']
}) {
  const [tab, setTab] = useState<ContentTab>('about')
  return (
    <PublicationContent publication={view} tiers={[]} draft={draft} tabs={{
      current: tab, path: (key) => `?tab=${key}`, extra,
      renderLink: (item, { children, ...props }) => (
        <button type="button" {...props} onClick={() => setTab(item.key as ContentTab)}>{children}</button>
      ),
    }} />
  )
}

describe('PublicationContent', () => {
  it('without tabs is one column: the parts folded or open, in the order they always had', () => {
    render(<PublicationContent publication={VIEW} tiers={[]} />)
    expect(screen.queryByTestId('detail-tabs')).toBeNull()
    for (const id of ['materials', 'detail-intro', 'detail-reward', 'detail-stats', 'detail-sponsor-intro']) {
      expect(screen.getByTestId(id)).toBeInTheDocument()
    }
    expect(screen.getByTestId('detail-reward').tagName).toBe('DETAILS')
  })

  it('with tabs shows the facts above and one part at a time, each tab with its own content', async () => {
    render(<Tabbed />)
    expect(screen.getByTestId('detail-facts')).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Parts of the quiz page' })
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual(['About', 'Reward', 'Materials', 'Rules'])
    expect(within(nav).getByTestId('page-tab-about')).toHaveAttribute('aria-current', 'page')
    expect(screen.getByTestId('detail-intro')).toBeInTheDocument()
    expect(screen.getByTestId('detail-stats')).toHaveTextContent('1 in the last 24 hours')
    expect(screen.queryByTestId('detail-reward')).toBeNull()
    await expectAccessible()

    await userEvent.click(screen.getByTestId('page-tab-reward'))
    expect(screen.getByTestId('detail-prize')).toHaveTextContent('A free espresso')
    expect(screen.getByTestId('page-tab-reward')).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByTestId('detail-intro')).toBeNull()
    await expectAccessible()

    await userEvent.click(screen.getByTestId('page-tab-materials'))
    expect(screen.getByTestId('material-title')).toHaveTextContent('Bean guide')
    expect(screen.getByTestId('detail-sponsor-intro')).toHaveTextContent('We roast coffee.')

    await userEvent.click(screen.getByTestId('page-tab-rules'))
    expect(screen.getByTestId('detail-rules')).toHaveTextContent('Time limit')
    expect(screen.getByTestId('detail-rules')).toHaveTextContent('Pass 2/2')
    await expectAccessible()
  })

  it('leaves out a tab without content: no reward before it is published, no materials without any', () => {
    render(<Tabbed draft view={{ ...VIEW, materials: [], sponsorIntro: null }} />)
    const nav = screen.getByRole('navigation', { name: 'Parts of the quiz page' })
    expect(within(nav).getAllByRole('button').map((b) => b.getAttribute('data-testid'))).toEqual(
      ['page-tab-about', 'page-tab-rules'])
  })

  it('puts what a host adds after the content of its tab, and keeps the tab for it', async () => {
    render(<Tabbed view={{ ...VIEW, materials: [], sponsorIntro: null }}
      extra={{ materials: <p data-testid="host-extra">The Sponsor page</p> }} />)
    await userEvent.click(screen.getByTestId('page-tab-materials'))
    expect(screen.getByTestId('host-extra')).toBeInTheDocument()
  })

  it('has the four tabs in the order of the plan', () => {
    expect(CONTENT_TABS).toEqual(['about', 'reward', 'materials', 'rules'])
  })
})
