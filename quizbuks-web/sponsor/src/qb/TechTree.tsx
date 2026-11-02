import { Button, cn } from '@jabiz/ui'
import { CircleCheck, CircleDashed, Hourglass, Lock, Minus, Play, Plus } from 'lucide-react'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { QB } from './messages'
import { Link } from './router'
import { DEFAULT_LAYOUT, FOLD_ABOVE, layoutTree, levelsOf, type Direction, type TreeEdge, type TreeNodeInput,
  type TreeState } from './tree'

/*
 * The picture of a sponsor's tree (Q85, D-Q85-9): inline SVG, no drawing library. The edges are drawn in the SVG;
 * each node is an HTML button or link in a foreignObject, so that keyboards and screen readers meet ordinary
 * controls (in level order), and the colours are the theme's tokens. Levels go left to right on a wide screen and
 * top to bottom on a phone. More than FOLD_ABOVE nodes are folded by level until a level is opened.
 */

export interface TechTreeNode extends TreeNodeInput {
  /** A person's state on the node (the taker's own, or the customer the sponsor looks at); none on the overview. */
  state?: TreeState | null
  /** The sponsor's overview: how many customers lit it, answered it without passing, or could start it. */
  counts?: { lit: number; answered: number; unlocked: number } | null
  /** Whether the publication accepts attempts now. */
  live?: boolean
  /** A second line under the name (a date, a status). */
  subtitle?: string | null
}

export interface TechTreeProps {
  nodes: readonly TechTreeNode[]
  /** The node to mark. */
  selected?: string | null
  /** Told when a node is picked; the node is a button then. */
  onSelect?: (id: string) => void
  /** Where a node leads; the node is a link then. */
  hrefOf?: (node: TechTreeNode) => string
  /** `auto`: by the screen's width. */
  direction?: Direction | 'auto'
  /** What the picture is of, for assistive technology. */
  label: string
  testId?: string
  className?: string
}

const WIDE = '(min-width: 640px)'

function useWide(): boolean {
  const [wide, setWide] = useState(() => typeof window !== 'undefined' && window.matchMedia?.(WIDE).matches)
  useEffect(() => {
    const query = window.matchMedia?.(WIDE)
    if (!query) return
    const listen = () => setWide(query.matches)
    query.addEventListener('change', listen)
    return () => query.removeEventListener('change', listen)
  }, [])
  return !!wide
}

const STATE_ICON: Record<TreeState, typeof Lock> = {
  LIT: CircleCheck, ANSWERED: CircleDashed, IN_PROGRESS: Hourglass, UNLOCKED: Play, LOCKED: Lock, CLOSED: Minus,
}

/** The node's look by state (theme tokens only, D-Q85-9); the overview (no state) is a plain card. */
export function stateClass(state: TreeState | null | undefined): string {
  switch (state) {
    case 'LIT': return 'bg-primary text-primary-foreground border-primary'
    case 'ANSWERED': return 'bg-primary/20 border-primary'
    case 'IN_PROGRESS': return 'bg-card border-primary border-dashed'
    case 'UNLOCKED': return 'bg-card border-[var(--chart-2)] shadow-[0_0_0_2px_var(--chart-2)]'
    case 'LOCKED': return 'bg-muted text-muted-foreground border-dashed'
    case 'CLOSED': return 'bg-muted/60 text-muted-foreground opacity-80'
    default: return 'bg-card'
  }
}

/** The state's icon and name, as a small inline label. */
export function StateLabel({ state, className }: { state: TreeState; className?: string }) {
  const { t } = useTranslation(QB)
  const Icon = STATE_ICON[state]
  return (
    <span className={cn('inline-flex items-center gap-1', className)} data-testid="tree-state" data-state={state}>
      <Icon aria-hidden className="size-3.5 shrink-0" />{t(`tree.state.${state}`)}
    </span>
  )
}

interface Drawn extends TreeNodeInput {
  /** A real node, or a folded level standing for its nodes. */
  node?: TechTreeNode
  folded?: { level: number; count: number }
}

/** What to draw: every node, or, above FOLD_ABOVE, the open levels' nodes and one block per folded level. */
function drawn(nodes: readonly TechTreeNode[], open: ReadonlySet<number>): Drawn[] {
  if (nodes.length <= FOLD_ABOVE) return nodes.map((node) => ({ ...node, node }))
  const levels = levelsOf(nodes)
  const levelOf = (id: string) => levels.get(id) ?? 1
  const blockId = (level: number) => `level:${level}`
  const visible = (id: string) => open.has(levelOf(id)) ? id : blockId(levelOf(id))
  const result: Drawn[] = []
  const folded = new Map<number, { count: number; requires: Map<string, TreeEdge> }>()
  for (const node of nodes) {
    const level = levelOf(node.id)
    const requires = node.requires.filter((edge) => levels.has(edge.id)).map((edge) => ({ ...edge, id: visible(edge.id) }))
    if (open.has(level)) {
      result.push({ ...node, node, requires: dedupe(requires) })
    } else {
      const block = folded.get(level) ?? { count: 0, requires: new Map() }
      block.count += 1
      for (const edge of requires) {
        if (edge.id !== blockId(level) && !block.requires.has(edge.id)) block.requires.set(edge.id, edge)
      }
      folded.set(level, block)
    }
  }
  for (const [level, block] of folded) {
    result.push({ id: blockId(level), name: '', requires: [...block.requires.values()], folded: { level, count: block.count } })
  }
  return result
}

function dedupe(edges: TreeEdge[]): TreeEdge[] {
  const seen = new Map<string, TreeEdge>()
  for (const edge of edges) if (!seen.has(edge.id)) seen.set(edge.id, edge)
  return [...seen.values()]
}

const ZOOMS = [0.6, 0.8, 1, 1.25, 1.5]

/** The tree as a picture. */
export function TechTree({ nodes, selected, onSelect, hrefOf, direction = 'auto', label, testId = 'tech-tree',
  className }: TechTreeProps) {
  const { t } = useTranslation(QB)
  const wide = useWide()
  const dir: Direction = direction === 'auto' ? (wide ? 'row' : 'column') : direction
  const [open, setOpen] = useState<Set<number>>(() => new Set())
  const [zoom, setZoom] = useState(2)
  const byId = useMemo(() => new Map(nodes.map((node) => [node.id, node])), [nodes])
  const layout = useMemo(() => layoutTree(drawn(nodes, open), { ...DEFAULT_LAYOUT, direction: dir }), [nodes, open, dir])
  const folded = nodes.length > FOLD_ABOVE
  const scale = ZOOMS[zoom]
  const namesOf = (edges: TreeEdge[]) => edges.map((edge) => byId.get(edge.id)?.name).filter(Boolean).join(', ')
  return (
    <figure className={cn('flex flex-col gap-2', className)} data-testid={testId} data-direction={dir}>
      <div className="flex items-center justify-end gap-1">
        <Button type="button" variant="ghost" size="icon" aria-label={t('tree.zoomOut')} disabled={zoom === 0}
          onClick={() => setZoom((z) => Math.max(0, z - 1))} data-testid={`${testId}-zoom-out`}>
          <Minus aria-hidden />
        </Button>
        <span className="text-muted-foreground w-12 text-center text-xs tabular-nums" aria-hidden>
          {Math.round(scale * 100)}%
        </span>
        <Button type="button" variant="ghost" size="icon" aria-label={t('tree.zoomIn')}
          disabled={zoom === ZOOMS.length - 1} onClick={() => setZoom((z) => Math.min(ZOOMS.length - 1, z + 1))}
          data-testid={`${testId}-zoom-in`}>
          <Plus aria-hidden />
        </Button>
      </div>
      <div className="overflow-auto rounded-lg border" data-testid={`${testId}-viewport`}>
        <svg role="group" aria-label={label} width={layout.width * scale} height={layout.height * scale}
          viewBox={`0 0 ${layout.width} ${layout.height}`} className="block max-w-none"
          data-testid={`${testId}-svg`}>
          <g aria-hidden="true" fill="none" strokeWidth={2}>
            {layout.edges.map((edge) => {
              const lit = byId.get(edge.from)?.state === 'LIT' && byId.get(edge.to)?.state !== 'LOCKED'
              return (
                <path key={`${edge.from}>${edge.to}`} d={edge.d} data-testid={`${testId}-edge`}
                  data-from={edge.from} data-to={edge.to} data-requirement={edge.requirement}
                  className={lit ? 'stroke-primary' : 'stroke-border'}
                  strokeDasharray={edge.requirement === 'ANSWERED' ? '6 4' : undefined} />
              )
            })}
          </g>
          {layout.levels.map((level) => level.nodes.map((place) => {
            const item = place.node
            const common = { x: place.x, y: place.y, width: DEFAULT_LAYOUT.nodeWidth, height: DEFAULT_LAYOUT.nodeHeight }
            if (item.folded) {
              return (
                <foreignObject key={item.id} {...common}>
                  <Button type="button" variant="outline" className="size-full flex-col gap-0.5 whitespace-normal"
                    onClick={() => setOpen((before) => new Set([...before, item.folded!.level]))}
                    data-testid={`${testId}-folded`} data-level={item.folded.level}>
                    <span className="font-medium">{t('tree.level', { n: item.folded.level })}</span>
                    <span className="text-muted-foreground text-xs">
                      {t('tree.foldedCount', { count: item.folded.count })} · {t('tree.open')}
                    </span>
                  </Button>
                </foreignObject>
              )
            }
            const node = item.node!
            const after = namesOf(node.requires)
            const description = [node.state ? t(`tree.state.${node.state}`) : null,
              after ? t('tree.after', { names: after }) : null].filter(Boolean).join('. ')
            const body = (
              <NodeBody node={node} after={after} />
            )
            const nodeClass = cn('flex size-full flex-col justify-center gap-0.5 rounded-lg border px-3 py-1.5 text-left',
              'text-sm leading-tight transition-colors hover:brightness-95 focus-visible:ring-ring focus-visible:ring-2',
              'focus-visible:outline-none', stateClass(node.state), selected === node.id && 'ring-ring ring-2')
            return (
              <foreignObject key={item.id} {...common}>
                {hrefOf
                  ? <Link to={hrefOf(node)} className={nodeClass} aria-label={`${node.name}. ${description}`}
                    data-testid={`${testId}-node`} {...{ 'data-id': node.id, 'data-state': node.state ?? undefined }}>
                    {body}
                  </Link>
                  : <button type="button" className={cn(nodeClass, 'w-full')} onClick={() => onSelect?.(node.id)}
                    aria-pressed={selected === node.id} aria-label={`${node.name}. ${description}`}
                    data-testid={`${testId}-node`} data-id={node.id} data-state={node.state ?? undefined}>
                    {body}
                  </button>}
              </foreignObject>
            )
          }))}
        </svg>
      </div>
      {folded && (
        <p className="text-muted-foreground text-xs" data-testid={`${testId}-folded-hint`}>{t('tree.foldedHint')}</p>
      )}
      <Legend nodes={nodes} />
    </figure>
  )
}

function NodeBody({ node, after }: { node: TechTreeNode; after: string }) {
  const { t } = useTranslation(QB)
  const Icon = node.state ? STATE_ICON[node.state] : null
  return (
    <>
      <span className="flex items-start gap-1.5">
        {Icon && <Icon aria-hidden className="mt-0.5 size-4 shrink-0" />}
        <span className="line-clamp-2 font-medium">{node.name}</span>
      </span>
      {node.counts
        ? <span className="flex flex-wrap gap-x-2 text-xs tabular-nums" data-testid="tree-counts">
          <span title={t('tree.count.lit')}>{t('tree.count.litShort', { count: node.counts.lit })}</span>
          <span title={t('tree.count.answered')}>{t('tree.count.answeredShort', { count: node.counts.answered })}</span>
          <span title={t('tree.count.unlocked')}>{t('tree.count.unlockedShort', { count: node.counts.unlocked })}</span>
        </span>
        : node.subtitle
          ? <span className="truncate text-xs opacity-80">{node.subtitle}</span>
          : after
            ? <span className="truncate text-xs opacity-80">{t('tree.after', { names: after })}</span>
            : null}
    </>
  )
}

/** What the colours mean: the states present in the tree, or the counts on the overview. */
function Legend({ nodes }: { nodes: readonly TechTreeNode[] }) {
  const { t } = useTranslation(QB)
  const states = [...new Set(nodes.map((node) => node.state).filter((s): s is TreeState => !!s))]
  const counted = nodes.some((node) => node.counts)
  if (states.length === 0 && !counted) return null
  return (
    <ul className="text-muted-foreground flex flex-wrap gap-x-3 gap-y-1 text-xs" aria-label={t('tree.legend')}
      data-testid="tree-legend">
      {states.map((state) => (
        <li key={state} className="inline-flex items-center gap-1">
          <span aria-hidden className={cn('inline-block size-3 rounded-sm border', stateClass(state))} />
          <StateLabel state={state} />
        </li>
      ))}
      {counted && <li>{t('tree.countsLegend')}</li>}
    </ul>
  )
}

/** A line of progress: lit of all, and what comes next (D-Q85-6). */
export function TreeProgress({ lit, total, next, className }: { lit: number; total: number; next: readonly string[];
  className?: string }): ReactNode {
  const { t } = useTranslation(QB)
  return (
    <p className={cn('text-sm', className)} data-testid="tree-progress">
      <span className="font-medium">{t('tree.progress', { lit, total })}</span>
      {' · '}
      {next.length > 0 ? t('tree.next', { names: next.join(', ') }) : t('tree.noNext')}
    </p>
  )
}
