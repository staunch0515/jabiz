import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { useState } from 'react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import '../messages'
import type { Quiz, VersionRow } from '../content/api'
import { EditingProvider, type Editing } from '../content/editing'
import { NO_TOPIC_CHANGES, topicChangesOf, type TopicChanges } from '../publishing/api'
import { topicsMessages } from './messages'
import { effectiveTopics, PublicationTopicsField } from './PublicationTopicsField'
import { QuizTopicsField } from './QuizTopicsField'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const TOPICS = [
  { topicId: 'coffee', name: JSON.stringify({ zh: '咖啡', ja: 'コーヒー', en: 'Coffee' }), active: true, mergedInto: null },
  { topicId: 'tea', name: JSON.stringify({ zh: '茶', ja: 'お茶', en: 'Tea' }), active: true, mergedInto: null },
  { topicId: 'old', name: JSON.stringify({ zh: '旧', ja: '旧', en: 'Old word' }), active: false, mergedInto: null },
  { topicId: 'cafe', name: JSON.stringify({ zh: '咖啡馆', ja: 'カフェ', en: 'Cafe' }), active: false,
    mergedInto: 'coffee' },
]

/** The server: the word list, and the quiz's topics saved as the server would (the revision going up). */
function server() {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/queries/qb.sponsor.topics':
        return json(200, { items: TOPICS, offset: 0, limit: 500 })
      case '/api/processes/QB_QUIZ_TOPICS_SET/latest':
        return json(200, { output: { quizId: 'q1', revision: 8, editedAt: '2026-11-08T00:00:00Z' } })
      default:
        return json(404, {})
    }
  })
  return { calls: (name: string) => posted.filter((p) => p.path === `/api/processes/${name}/latest`)
    .map((p) => p.body) }
}

const QUIZ = { quizId: 'q1', title: 'Coffee basics', kind: 'QUIZ', language: 'en', intro: null, cover: null,
  timeLimitSec: null, status: 'DRAFT', latestVersionNo: 0, versionLabel: null, revision: 7, editedAt: '',
  changedSinceVersion: true, questionCount: 0, materialCount: 0, clonedFrom: null, translationLanguages: [],
  topics: ['coffee', 'old'], customTopics: ['Latte art'] } as Quiz

function wrap(children: React.ReactNode, editing?: Partial<Editing>) {
  const value: Editing = { quizId: 'q1', revision: () => 7, edited: vi.fn(async () => {}), failed: vi.fn(),
    stale: false, ...editing }
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <EditingProvider value={value}>{children}</EditingProvider>
    </QueryClientProvider>
  )
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(async () => {
  session.clear()
  session.store('access', 'refresh')
  await i18next.changeLanguage('en')
})
afterEach(() => vi.restoreAllMocks())

describe('topics in the sponsor console (F-S9)', () => {
  it('have every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(topicsMessages.en)
    expect(keys(topicsMessages.zh)).toEqual(english)
    expect(keys(topicsMessages.ja)).toEqual(english)
  })

  it('shows a quiz\'s topics in the console\'s language and saves each change with its revision', async () => {
    const { calls } = server()
    const edited = vi.fn(async () => {})
    const { container } = render(wrap(<QuizTopicsField quiz={QUIZ} />, { edited }))
    const coffee = await screen.findByTestId('quiz-topic-coffee')
    await waitFor(() => expect(coffee).toHaveTextContent('Coffee'))
    // A word no longer offered is marked; a custom word says so.
    expect(screen.getByTestId('quiz-topic-old')).toHaveTextContent('Old wordNo longer offered')
    expect(screen.getByTestId('quiz-custom-0')).toHaveTextContent('Latte artOwn word')
    expect(screen.getByTestId('quiz-topics-count')).toHaveTextContent('3 of 10')
    await userEvent.click(within(screen.getByTestId('quiz-topic-old')).getByRole('button', { name: 'Remove Old word' }))
    await waitFor(() => expect(calls('QB_QUIZ_TOPICS_SET')).toContainEqual({ quizId: 'q1', topics: ['coffee'],
      customTopics: ['Latte art'], baseRevision: 7 }))
    await waitFor(() => expect(edited).toHaveBeenCalled())
    // Words offered only: neither the deactivated nor the merged one, nor one the quiz has.
    await userEvent.click(screen.getByTestId('quiz-topics-add-word'))
    expect(await screen.findByRole('option', { name: 'Tea' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Coffee' })).toBeNull()
    expect(screen.queryByRole('option', { name: 'Cafe' })).toBeNull()
    expect(screen.queryByRole('option', { name: 'Old word' })).toBeNull()
    await userEvent.click(screen.getByRole('option', { name: 'Tea' }))
    await waitFor(() => expect(calls('QB_QUIZ_TOPICS_SET')).toContainEqual(expect.objectContaining({
      topics: ['coffee', 'old', 'tea'] })))
    // A custom word, as the server keeps it.
    await userEvent.type(screen.getByTestId('quiz-topics-add-custom'), '  Cold   brew {Enter}')
    await waitFor(() => expect(calls('QB_QUIZ_TOPICS_SET')).toContainEqual(expect.objectContaining({
      customTopics: ['Latte art', 'Cold brew'] })))
    await expectAccessible(container)
  })

  it('offers nothing more once the topics are full', async () => {
    server()
    const full = { ...QUIZ, topics: ['coffee', 'tea'], customTopics: ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'] } as Quiz
    render(wrap(<QuizTopicsField quiz={full} />))
    expect(await screen.findByTestId('quiz-topics-add-full')).toBeInTheDocument()
    expect(screen.getByTestId('quiz-topics-add-word')).toBeDisabled()
    expect(screen.getByTestId('quiz-topics-add-custom')).toBeDisabled()
  })

  it('makes a publication\'s topics of its version\'s with its changes, as the server does', () => {
    const version = { topics: ['history', 'art'], customTopics: ['Edo period', 'Ukiyo-e'] }
    const changes: TopicChanges = { add: ['music', 'history'], remove: ['art'], customAdd: ['Hokusai', 'edo PERIOD'],
      customRemove: ['ukiyo-E'] }
    expect(effectiveTopics(version, changes)).toEqual({ codes: ['history', 'music'], custom: ['Edo period', 'Hokusai'] })
    expect(effectiveTopics(null, NO_TOPIC_CHANGES)).toEqual({ codes: [], custom: [] })
    expect(topicChangesOf('{"add":["a"],"customRemove":["X"]}')).toEqual({ add: ['a'], remove: [], customAdd: [],
      customRemove: ['X'] })
    expect(topicChangesOf('broken')).toEqual(NO_TOPIC_CHANGES)
  })

  it('lets the wizard leave out the version\'s topics, use them again and add its own', async () => {
    server()
    const version = { versionId: 'v1', topics: ['coffee'], customTopics: ['Latte art'] } as VersionRow
    let latest: TopicChanges = NO_TOPIC_CHANGES
    function Host() {
      const [changes, setChanges] = useState<TopicChanges>(NO_TOPIC_CHANGES)
      latest = changes
      return <PublicationTopicsField version={version} changes={changes} onChange={setChanges} disabled={false} />
    }
    const { container } = render(wrap(<Host />))
    const coffee = await screen.findByTestId('publication-topic-coffee')
    await waitFor(() => expect(coffee).toHaveTextContent('CoffeeFrom the version'))
    await userEvent.click(within(coffee).getByRole('button', { name: 'Leave out Coffee' }))
    expect(latest.remove).toEqual(['coffee'])
    expect(screen.getByTestId('publication-topic-coffee')).toHaveTextContent('Left out')
    await userEvent.click(within(screen.getByTestId('publication-topic-coffee'))
      .getByRole('button', { name: 'Use Coffee again' }))
    expect(latest.remove).toEqual([])
    await userEvent.click(within(screen.getByTestId('publication-version-custom-0'))
      .getByRole('button', { name: 'Leave out Latte art' }))
    expect(latest.customRemove).toEqual(['Latte art'])
    await userEvent.click(screen.getByTestId('publication-topics-add-word'))
    await userEvent.click(await screen.findByRole('option', { name: 'Tea' }))
    expect(latest.add).toEqual(['tea'])
    await userEvent.type(screen.getByTestId('publication-topics-add-custom'), 'Espresso{Enter}')
    expect(latest.customAdd).toEqual(['Espresso'])
    expect(screen.getByTestId('publication-topics-count')).toHaveTextContent('3 of 10')
    await expectAccessible(container)
  })
})
