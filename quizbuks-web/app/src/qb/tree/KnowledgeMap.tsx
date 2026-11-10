import { Badge, Button, cn } from '@jabiz/ui'
import { type KeyboardEvent, type ReactNode, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { pickText } from '../legal'
import { QB } from '../messages'
import { layout } from './layout'
import { gateKey } from './messages'
import type { Condition, Graph, GraphNode, NodeState } from './states'

/** A node's daily count of the people who answered and have not lit (F-S3f): null under the minimum group. */
export interface UnlitCount {
  count: number | null
  /** The last day counted (`YYYY-MM-DD`). */
  countedOn: string
}

/**
 * The shared knowledge map (F-S3, docs/quizbuks/plans/impl/F-S3.md §3), replacing Q85's tree: the whole map laid out
 * automatically by levels (what must come first above), each node with its state for the person when there is one, and
 * a list of the same nodes as the text alternative. Keyboard: the map is one tab stop; arrow keys move between nodes
 * (left and right within a level, up and down to the nearest node of the next level), Enter or Space shows the node.
 * The platform's maps and the Sponsors' are marked differently (the kind's badge, the frame). Colours are tokens only;
 * every state is also said in words.
 */

/** A publication the person can start now, bound to a node (from `qb.app.feed` with `tree`). */
export interface Startable {
  publicationId: string
  name: string
}

export interface KnowledgeMapProps {
  graph: Graph
  /** Each node's state for the person; none: nobody's (a Sponsor's own map, the public page). */
  states?: ReadonlyMap<string, NodeState>
  /** The publications the person can start now, by node: the "can answer" links. */
  startable?: ReadonlyMap<string, Startable[]>
  /** The page's link to a publication. */
  publicationLink?: (publication: Startable) => ReactNode
  /** Per node, how many Takers lit it (the Sponsor's own map; null: under the minimum group). */
  litCounts?: ReadonlyMap<string, number | null>
  /**
   * F-S3f: per node counted by the daily job, how many answered its publications and have not lit it, and the last day
   * counted (null count: under the minimum group). A node not counted yet has none.
   */
  unlitCounts?: ReadonlyMap<string, UnlitCount>
  /** The view first shown. */
  initialView?: 'graph' | 'list'
  /** F-S4c: per node, the granted credit lighting it gives (a map Sponsors light), as the page formats it. */
  grants?: ReadonlyMap<string, string>
}

const CARD_W = 208
const CARD_H = 92
const GAP_X = 24
const GAP_Y = 56

const STATE_CLASS: Record<NodeState, string> = {
  LIT: 'border-primary bg-primary/10',
  CAN_ANSWER: 'border-primary border-dashed',
  WAITING: 'border-border border-dashed',
  MISSING: 'border-border',
  LOCKED: 'border-border bg-muted text-muted-foreground',
}

const STATE_BADGE: Record<NodeState, 'default' | 'secondary' | 'outline'> = {
  LIT: 'default', CAN_ANSWER: 'secondary', WAITING: 'outline', MISSING: 'outline', LOCKED: 'outline',
}

export function KnowledgeMap({ graph, states, startable, publicationLink, litCounts, unlitCounts, grants,
  initialView = 'graph' }:
  KnowledgeMapProps) {
  const { t, i18n } = useTranslation(QB)
  const [view, setView] = useState<'graph' | 'list'>(initialView)
  const [selected, setSelected] = useState<string | null>(null)
  const nodes = useMemo(() => new Map(graph.nodes.map((node) => [node.nodeId, node])), [graph])
  const placement = useMemo(() => layout(graph.nodes, graph.treeId), [graph])
  const name = (text: GraphNode['name'] | null | undefined) => pickText(text, i18n.language).text
  const mapName = name(graph.name)

  return (
    <section aria-label={t('tree.graphLabel', { name: mapName })} data-testid="knowledge-map"
      data-owner-kind={graph.ownerKind}
      className={cn('flex flex-col gap-4 rounded-lg border p-4',
        graph.ownerKind === 'PLATFORM' ? 'border-primary border-2' : 'border-border')}>
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant={graph.ownerKind === 'PLATFORM' ? 'default' : 'secondary'} data-testid="knowledge-map-kind">
          {t(`tree.kinds.${graph.ownerKind}`)}
        </Badge>
        <span className="font-medium">{mapName}</span>
        <div role="group" aria-label={t('tree.views.label')} className="ms-auto flex gap-1">
          {(['graph', 'list'] as const).map((option) => (
            <Button key={option} type="button" size="sm" variant={view === option ? 'default' : 'outline'}
              aria-pressed={view === option} data-testid={`knowledge-map-view-${option}`}
              onClick={() => setView(option)}>
              {t(`tree.views.${option}`)}
            </Button>
          ))}
        </div>
      </div>
      {graph.description && name(graph.description) && (
        <p className="text-muted-foreground text-sm whitespace-pre-line">{name(graph.description)}</p>
      )}
      {graph.nodes.length === 0 ? (
        <p className="text-muted-foreground text-sm" data-testid="knowledge-map-empty">{t('tree.empty')}</p>
      ) : view === 'graph' ? (
        <MapView graph={graph} placement={placement} states={states} selected={selected} onSelect={setSelected}
          nodeName={(id) => name(nodes.get(id)?.name)} />
      ) : (
        <ListView placement={placement} nodes={nodes} states={states} selected={selected} onSelect={setSelected}
          nodeName={(id) => name(nodes.get(id)?.name)} />
      )}
      {selected && nodes.get(selected) && (
        <NodeDetails node={nodes.get(selected)!} state={states?.get(selected)}
          startable={startable?.get(selected) ?? []} publicationLink={publicationLink}
          litCount={litCounts?.has(selected) ? litCounts.get(selected) : undefined}
          unlitCount={unlitCounts?.get(selected)} grant={grants?.get(selected)}
          nodeName={(id) => name(nodes.get(id)?.name)} />
      )}
    </section>
  )
}

interface ViewProps {
  states?: ReadonlyMap<string, NodeState>
  selected: string | null
  onSelect: (nodeId: string) => void
  nodeName: (nodeId: string) => string
}

/** The drawn map: cards on levels, lines from what a node names (above) to it. */
function MapView({ graph, placement, states, selected, onSelect, nodeName }:
  ViewProps & { graph: Graph; placement: ReturnType<typeof layout> }) {
  const { t } = useTranslation(QB)
  const widest = Math.max(...placement.layers.map((layer) => layer.length))
  const width = widest * (CARD_W + GAP_X) - GAP_X
  const height = placement.layers.length * (CARD_H + GAP_Y) - GAP_Y
  const position = (id: string) => {
    const placed = placement.placed.get(id)!
    const inLayer = placement.layers[placed.layer].length
    const offset = ((widest - inLayer) * (CARD_W + GAP_X)) / 2
    return { x: offset + placed.order * (CARD_W + GAP_X), y: placed.layer * (CARD_H + GAP_Y) }
  }
  // One tab stop: the selected node, else the first; arrows move the focus (roving tabindex).
  const [focused, setFocused] = useState<string>(selected ?? placement.layers[0][0])
  const buttons = useRef(new Map<string, HTMLButtonElement>())
  const move = (to: string | undefined) => {
    if (!to) return
    setFocused(to)
    buttons.current.get(to)?.focus()
  }
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, id: string) => {
    const placed = placement.placed.get(id)!
    const layer = placement.layers[placed.layer]
    const nearest = (row: string[] | undefined) => {
      if (!row) return undefined
      const x = position(id).x
      return [...row].sort((a, b) => Math.abs(position(a).x - x) - Math.abs(position(b).x - x))[0]
    }
    const target = {
      ArrowRight: layer[placed.order + 1],
      ArrowLeft: layer[placed.order - 1],
      ArrowDown: nearest(placement.layers[placed.layer + 1]),
      ArrowUp: nearest(placement.layers[placed.layer - 1]),
      Home: layer[0],
      End: layer[layer.length - 1],
    }[event.key]
    if (event.key in { ArrowRight: 1, ArrowLeft: 1, ArrowDown: 1, ArrowUp: 1, Home: 1, End: 1 }) {
      event.preventDefault()
      move(target)
    }
  }
  return (
    <>
      <p className="text-muted-foreground text-xs" id={`knowledge-map-hint-${graph.treeId}`}>{t('tree.keyboardHint')}</p>
      {/* Focusable so that a keyboard can scroll a map wider than the screen (axe: scrollable-region-focusable). */}
      <div className="overflow-auto" tabIndex={0} role="region" aria-label={t('tree.scrollLabel')}
        data-testid="knowledge-map-scroll">
        <div className="relative" style={{ width, height }}>
          <svg aria-hidden="true" className="text-border absolute inset-0" width={width} height={height}>
            {placement.edges.map((edge) => {
              const from = position(edge.from)
              const to = position(edge.to)
              return (
                <line key={`${edge.from}-${edge.to}`} x1={from.x + CARD_W / 2} y1={from.y + CARD_H}
                  x2={to.x + CARD_W / 2} y2={to.y} stroke="currentColor" strokeWidth={2} />
              )
            })}
          </svg>
          <div role="group" aria-describedby={`knowledge-map-hint-${graph.treeId}`}>
            {graph.nodes.map((node) => {
              const { x, y } = position(node.nodeId)
              const state = states?.get(node.nodeId)
              return (
                <button key={node.nodeId} type="button" data-testid="knowledge-map-node" data-node-id={node.nodeId}
                  data-state={state ?? ''}
                  ref={(element) => {
                    if (element) buttons.current.set(node.nodeId, element)
                    else buttons.current.delete(node.nodeId)
                  }}
                  tabIndex={focused === node.nodeId ? 0 : -1}
                  aria-pressed={selected === node.nodeId}
                  aria-label={state ? t('tree.nodeLabel', { name: nodeName(node.nodeId), state: t(`tree.states.${state}`) })
                    : t('tree.noState', { name: nodeName(node.nodeId) })}
                  onFocus={() => setFocused(node.nodeId)}
                  onKeyDown={(event) => onKeyDown(event, node.nodeId)}
                  onClick={() => onSelect(node.nodeId)}
                  className={cn('bg-background focus-visible:ring-ring absolute flex flex-col items-start gap-1',
                    'rounded-md border-2 p-2 text-start text-sm focus-visible:ring-2 focus-visible:outline-none',
                    state ? STATE_CLASS[state] : 'border-border',
                    selected === node.nodeId && 'ring-ring ring-2')}
                  style={{ left: x, top: y, width: CARD_W, height: CARD_H }}>
                  <span className="line-clamp-2 font-medium">{nodeName(node.nodeId)}</span>
                  {state && <Badge variant={STATE_BADGE[state]}>{t(`tree.states.${state}`)}</Badge>}
                </button>
              )
            })}
          </div>
        </div>
      </div>
    </>
  )
}

/** The text alternative: the same nodes by level, each with its state. */
function ListView({ placement, nodes, states, selected, onSelect, nodeName }:
  ViewProps & { placement: ReturnType<typeof layout>; nodes: Map<string, GraphNode> }) {
  const { t } = useTranslation(QB)
  return (
    <div className="flex flex-col gap-3" data-testid="knowledge-map-list">
      {placement.layers.map((layer, index) => (
        <section key={layer.join()} aria-labelledby={`knowledge-map-layer-${index}`}>
          <h3 id={`knowledge-map-layer-${index}`} className="text-sm font-semibold">
            {t('tree.layer', { n: index + 1 })}
          </h3>
          <ul className="mt-1 flex flex-col gap-1">
            {layer.filter((id) => nodes.has(id)).map((id) => {
              const state = states?.get(id)
              return (
                <li key={id} className="flex flex-wrap items-center gap-2" data-testid="knowledge-map-list-node"
                  data-node-id={id} data-state={state ?? ''}>
                  <Button type="button" variant="link" className="h-auto p-0" aria-pressed={selected === id}
                    onClick={() => onSelect(id)}>
                    {nodeName(id)}
                  </Button>
                  {state && <Badge variant={STATE_BADGE[state]}>{t(`tree.states.${state}`)}</Badge>}
                </li>
              )
            })}
          </ul>
        </section>
      ))}
    </div>
  )
}

/** One node: what it is to the person, what lighting it gives, its condition marked, and what can be answered. */
function NodeDetails({ node, state, startable, publicationLink, litCount, unlitCount, grant, nodeName }: {
  node: GraphNode
  grant?: string
  state?: NodeState
  startable: Startable[]
  publicationLink?: (publication: Startable) => ReactNode
  litCount?: number | null
  unlitCount?: UnlitCount
  nodeName: (nodeId: string) => string
}) {
  const { t, i18n } = useTranslation(QB)
  const description = pickText(node.description, i18n.language).text
  const gates = node.gates.map(gateKey).filter((key): key is string => key !== null)
  const refName = (id: string) => {
    const ref = node.refs.find((r) => r.node === id)
    return (ref?.name ? pickText(ref.name, i18n.language).text : '') || nodeName(id) || t('tree.condition.unknownNode')
  }
  const refLit = (id: string) => node.refs.find((r) => r.node === id)?.lit
  return (
    <section aria-labelledby={`knowledge-map-node-${node.nodeId}`} data-testid="knowledge-map-details"
      className="bg-muted/40 flex flex-col gap-2 rounded-md border p-3">
      <h3 id={`knowledge-map-node-${node.nodeId}`} className="font-semibold">
        {nodeName(node.nodeId)}
        {state && <Badge className="ms-2" variant={STATE_BADGE[state]}>{t(`tree.states.${state}`)}</Badge>}
      </h3>
      {state && <p className="text-sm" data-testid="knowledge-map-state-hint">{t(`tree.stateHints.${state}`)}</p>}
      {state === 'CAN_ANSWER' && startable.length > 0 && (
        <ul className="flex flex-col gap-1 text-sm" data-testid="knowledge-map-startable">
          {startable.map((publication) => (
            <li key={publication.publicationId}>{publicationLink ? publicationLink(publication) : publication.name}</li>
          ))}
        </ul>
      )}
      {state === 'LOCKED' && node.unmet.length > 0 && (
        <div className="text-sm" data-testid="knowledge-map-unmet">
          <span>{t('tree.unmet')}</span>
          <ul className="list-disc ps-5">
            {node.unmet.map((entry) => <li key={entry}>{refName(entry.split('/')[1] ?? entry)}</li>)}
          </ul>
        </div>
      )}
      {(state === 'MISSING' || !state) && (
        <ConditionView condition={node.condition} refName={refName} refLit={state ? refLit : () => undefined} />
      )}
      <div className="text-sm">
        <span className="font-medium">{t('tree.gives')}</span>
        {description || gates.length > 0 || grant ? (
          <div data-testid="knowledge-map-gives">
            {description && <p className="whitespace-pre-line">{description}</p>}
            {gates.map((key) => <p key={key}>{t(`tree.gates.${key}`)}</p>)}
            {grant && <p data-testid="knowledge-map-grant">{t('tree.grant', { amount: grant })}</p>}
          </div>
        ) : <p className="text-muted-foreground">{t('tree.noGives')}</p>}
      </div>
      {litCount !== undefined && (
        <p className="text-muted-foreground text-sm" data-testid="knowledge-map-lit-count">
          {litCount === null ? t('tree.litCountHidden') : t('tree.litCount', { count: litCount })}
        </p>
      )}
      {unlitCount && (
        <p className="text-muted-foreground text-sm" data-testid="knowledge-map-unlit-count">
          {unlitCount.count === null ? t('tree.unlitCountHidden', { day: unlitCount.countedOn })
            : t('tree.unlitCount', { count: unlitCount.count, day: unlitCount.countedOn })}
        </p>
      )}
    </section>
  )
}

/** A condition as nested lists, each node marked lit or not when the person's state is known. */
function ConditionView({ condition, refName, refLit }: {
  condition: Condition
  refName: (id: string) => string
  refLit: (id: string) => boolean | undefined
}) {
  const { t } = useTranslation(QB)
  if ('op' in condition) {
    return (
      <div className="text-sm" data-testid="knowledge-map-condition">
        <span>{condition.op === 'AT_LEAST' ? t('tree.condition.AT_LEAST', { n: condition.n })
          : t(`tree.condition.${condition.op}`)}</span>
        <ul className="list-disc ps-5">
          {condition.items.map((item, index) => (
            <li key={index}><ConditionView condition={item} refName={refName} refLit={refLit} /></li>
          ))}
        </ul>
      </div>
    )
  }
  if ('node' in condition) {
    const lit = refLit(condition.node)
    return (
      <span>
        {t('tree.condition.node', { name: refName(condition.node) })}
        {condition.beforeAnswer && <> ({t('tree.condition.beforeAnswer')})</>}
        {lit !== undefined && <> — {lit ? t('tree.condition.lit') : t('tree.condition.notLit')}</>}
      </span>
    )
  }
  return <span>{t('tree.condition.passed')}</span>
}
