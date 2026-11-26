import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import type { PublicationView } from '../play'
import { CONTENT_TABS, FORMER_TABS, PublicationContent, type ContentTab, type ContentTabs } from './PublicationContent'
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
  const [tab, setTab] = useState<ContentTab>('materials')
  return (
    <PublicationContent publication={view} tiers={[]} draft={draft} tabs={{
      current: tab, path: (key) => `?tab=${key}`, extra,
      renderLink: (item, { children, ...props }) => (
        <button type="button" {...props} onClick={() => setTab(item.key as ContentTab)}>{children}</button>
      ),
    }} />
  )
}

describe('PublicationContent (Q113b-2: x.com\'s post detail)', () => {
  it('reads as a post: the author row, the name and introduction, the picture, the line of when and how many', () => {
    render(<Tabbed />)
    const author = screen.getByTestId('detail-author')
    expect(within(author).getByTestId('detail-sponsor')).toHaveTextContent('Acme')
    expect(author).toHaveTextContent('Sponsor')
    // No page published (and the console's preview never links): no "Page" pill.
    expect(screen.queryByTestId('detail-sponsor-page')).toBeNull()
    expect(screen.getByTestId('detail-name')).toHaveTextContent('Coffee week')
    expect(screen.getByTestId('detail-name')).toHaveClass('text-[17px]', 'font-bold')
    expect(screen.getByTestId('detail-intro')).toHaveTextContent('Two easy questions.')
    expect(screen.getByTestId('detail-hero')).toHaveClass('rounded-[16px]', 'aspect-video', 'border')
    // How many answered, and when it ends; the date it went up (a week and more ago).
    const line = screen.getByTestId('detail-published')
    expect(within(line).getByTestId('detail-completions')).toHaveTextContent('5')
    expect(line).toHaveTextContent(/Posted Nov 1.*5 answered.*ends Nov 8/)
  })

  it('has the four facts that matter between two dividers, the reward in its colour, and no completion counts (P8)', () => {
    render(<Tabbed />)
    const facts = screen.getByTestId('detail-facts')
    expect(facts).toHaveClass('border-y', 'h-12')
    expect(within(facts).getByTestId('detail-method-fact')).toHaveClass('text-qb-reward-foreground')
    expect(within(facts).getByTestId('detail-method')).toHaveAttribute('data-method', 'OFFLINE_REDEEM')
    expect(facts).toHaveTextContent('Up to 100 Kudos')
    expect(facts).toHaveTextContent('2 questions')
    expect(facts).toHaveTextContent('5 min')
    expect(facts).toHaveTextContent('2 to pass')
    // The counts by the hour and the day are gone (Q113b-2, confirmed 2026-11-26); the total is in the line above.
    expect(screen.queryByTestId('detail-stats')).toBeNull()
    expect(screen.queryByText('Last 24 hours')).toBeNull()
  })

  it('has two tabs, the materials and the rules, the reward under the rules', async () => {
    render(<Tabbed />)
    const nav = screen.getByRole('navigation', { name: 'Parts of the quiz page' })
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual(['Materials 1', 'Rules'])
    expect(within(nav).getByTestId('page-tab-materials')).toHaveAttribute('aria-current', 'page')
    expect(screen.getByTestId('material-title')).toHaveTextContent('Bean guide')
    expect(screen.queryByTestId('detail-reward')).toBeNull()
    await expectAccessible()

    await userEvent.click(screen.getByTestId('page-tab-rules'))
    expect(screen.getByTestId('page-tab-rules')).toHaveAttribute('aria-current', 'page')
    expect(screen.getByTestId('detail-rules')).toHaveTextContent('Time limit')
    expect(screen.getByTestId('detail-rules')).toHaveTextContent('Pass 2/2')
    const reward = screen.getByTestId('detail-reward')
    expect(within(reward).getByRole('heading', { name: 'Reward' })).toBeInTheDocument()
    expect(within(reward).getByTestId('detail-prize')).toHaveTextContent('A free espresso')
    await expectAccessible()
  })

  it('says so when there are no materials, and puts what a host adds after the content of its tab', async () => {
    const { unmount } = render(<Tabbed view={{ ...VIEW, materials: [] }} />)
    expect(screen.getByTestId('page-tab-materials')).toHaveTextContent('Materials 0')
    expect(screen.getByTestId('detail-no-materials')).toBeInTheDocument()
    unmount()
    render(<Tabbed view={{ ...VIEW, materials: [] }} extra={{ materials: <p data-testid="host-extra">The Sponsor page</p>,
      reward: <p data-testid="host-reward">Refer a friend</p>, rules: <p data-testid="host-rules">Report</p> }} />)
    expect(screen.getByTestId('host-extra')).toBeInTheDocument()
    expect(screen.queryByTestId('detail-no-materials')).toBeNull()
    await userEvent.click(screen.getByTestId('page-tab-rules'))
    // The referral panel at the end of the reward, the rules' own after it.
    expect(within(screen.getByTestId('detail-reward')).getByTestId('host-reward')).toBeInTheDocument()
    expect(screen.getByTestId('host-rules')).toBeInTheDocument()
  })

  it('shows the entry fee and the node above the tabs when a publication has them, whichever tab is open', async () => {
    render(<Tabbed view={{ ...VIEW, entryFee: 30, nodeName: { en: 'Latte art' }, nodeLit: false } as PublicationView} />)
    expect(screen.getByTestId('detail-entry-fee')).toHaveTextContent('30 Kudos')
    expect(screen.getByTestId('detail-node-state')).toHaveTextContent('Latte art · lit it by passing')
    await userEvent.click(screen.getByTestId('page-tab-rules'))
    expect(screen.getByTestId('detail-entry-fee')).toBeInTheDocument()
    expect(screen.getByTestId('detail-rules')).toHaveTextContent('Entry fee')
    cleanup()
    render(<Tabbed />)
    expect(screen.queryByTestId('detail-more-facts')).toBeNull()
  })

  it('reads "–" for what only a publication sets in a preview, without its age, end or reward', async () => {
    render(<Tabbed draft />)
    expect(screen.getByTestId('detail-published')).not.toHaveTextContent('Posted')
    expect(screen.getByTestId('detail-published')).not.toHaveTextContent('ends')
    expect(screen.getByTestId('detail-method-fact')).toHaveTextContent('–')
    await userEvent.click(screen.getByTestId('page-tab-rules'))
    expect(screen.queryByTestId('detail-reward')).toBeNull()
  })

  it('without tabs has the materials and then the rules', () => {
    render(<PublicationContent publication={VIEW} tiers={[]} />)
    expect(screen.queryByTestId('detail-tabs')).toBeNull()
    expect(screen.getByTestId('material-title')).toBeInTheDocument()
    expect(screen.getByTestId('detail-rules')).toBeInTheDocument()
    expect(screen.getByTestId('detail-reward')).toBeInTheDocument()
  })

  it('has the two tabs of the prototype, and knows where the tabs of before are now', () => {
    expect(CONTENT_TABS).toEqual(['materials', 'rules'])
    expect(FORMER_TABS).toEqual({ about: 'materials', reward: 'rules' })
  })
})
