import { cn } from '@jabiz/ui'
import type { SVGProps } from 'react'

/*
 * QuizBuks's illustrations (docs/quizbuks/plans/Q32-visual-refresh.md): drawn here as inline SVG so that they ship
 * with the app, need no licence and follow the appearance through the brand tokens (qb/brand.css). All are
 * decorative: aria-hidden, the text beside them says what the page is about.
 */

type Art = SVGProps<SVGSVGElement> & { className?: string }

const base = (className?: string) => ({
  'aria-hidden': true as const,
  focusable: 'false' as const,
  className: cn('block', className),
})

/** The wordmark: a coin with a leaf, then the name. */
export function Wordmark({ className, name, compact }: { className?: string; name: string; compact?: boolean }) {
  return (
    <span className={cn('inline-flex items-center gap-2', className)} data-testid="wordmark">
      <CoinMark className={compact ? 'size-7' : 'size-9'} />
      <span className={cn('font-semibold tracking-tight', compact ? 'text-base' : 'text-2xl')}>{name}</span>
    </span>
  )
}

/** The coin with a leaf: the app's mark. */
export function CoinMark({ className, ...rest }: Art) {
  return (
    <svg viewBox="0 0 48 48" {...base(className)} {...rest}>
      <circle cx="24" cy="24" r="22" fill="var(--qb-coin)" />
      <circle cx="24" cy="24" r="17" fill="none" stroke="var(--qb-coin-deep)" strokeWidth="2.5" />
      <path d="M24 34c-7 0-9-7-9-11 7 0 11 3 11 9 0 .7 0 1.4-.2 2H24z" fill="var(--qb-leaf-deep)" />
      <path d="M24 34c7 0 9-7 9-11-7 0-11 3-11 9 0 .7 0 1.4.2 2H24z" fill="var(--qb-leaf)" />
      <path d="M24 33V22" stroke="var(--qb-ink)" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  )
}

/** Answering: a card of questions with a ticked answer. */
export function QuizArt({ className, ...rest }: Art) {
  return (
    <svg viewBox="0 0 240 180" {...base(className)} {...rest}>
      <ellipse cx="120" cy="150" rx="92" ry="14" fill="var(--qb-sky)" />
      <rect x="52" y="30" width="136" height="110" rx="14" fill="var(--card)" stroke="var(--border)" strokeWidth="2" />
      <rect x="68" y="48" width="78" height="10" rx="5" fill="var(--qb-ink)" opacity=".85" />
      <rect x="68" y="66" width="104" height="8" rx="4" fill="var(--muted-foreground)" opacity=".5" />
      <rect x="68" y="88" width="104" height="16" rx="8" fill="var(--qb-sky)" stroke="var(--qb-leaf-deep)" strokeWidth="2" />
      <path d="M76 96l5 5 9-10" fill="none" stroke="var(--qb-leaf-deep)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      <rect x="68" y="112" width="104" height="16" rx="8" fill="none" stroke="var(--border)" strokeWidth="2" />
      <circle cx="190" cy="42" r="22" fill="var(--qb-coin)" />
      <circle cx="190" cy="42" r="15" fill="none" stroke="var(--qb-coin-deep)" strokeWidth="2.5" />
      <path d="M186 36l4 12 4-12" fill="none" stroke="var(--qb-ink)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M26 118c10-18 30-18 40 0" fill="none" stroke="var(--qb-leaf)" strokeWidth="4" strokeLinecap="round" />
      <path d="M46 118V96" stroke="var(--qb-leaf-deep)" strokeWidth="4" strokeLinecap="round" />
    </svg>
  )
}

/** Rewards: a stack of coins growing from a leaf. */
export function RewardArt({ className, ...rest }: Art) {
  return (
    <svg viewBox="0 0 240 180" {...base(className)} {...rest}>
      <ellipse cx="120" cy="152" rx="92" ry="14" fill="var(--qb-sky)" />
      {[0, 1, 2, 3].map((i) => (
        <g key={i} transform={`translate(0 ${-i * 16})`}>
          <ellipse cx="110" cy="136" rx="46" ry="14" fill="var(--qb-coin-deep)" />
          <ellipse cx="110" cy="130" rx="46" ry="14" fill="var(--qb-coin)" />
        </g>
      ))}
      <ellipse cx="110" cy="82" rx="30" ry="8" fill="none" stroke="var(--qb-coin-deep)" strokeWidth="2" />
      <path d="M150 90c22-26 48-30 64-28-4 22-22 42-50 44" fill="var(--qb-leaf)" />
      <path d="M150 90c22-26 48-30 64-28-4 22-22 42-50 44" fill="none" stroke="var(--qb-leaf-deep)" strokeWidth="2" />
      <path d="M152 104c14-14 30-26 54-36" fill="none" stroke="var(--qb-leaf-deep)" strokeWidth="2" strokeLinecap="round" />
      <circle cx="40" cy="48" r="4" fill="var(--qb-coin)" />
      <circle cx="200" cy="140" r="5" fill="var(--qb-leaf)" />
      <circle cx="60" cy="110" r="3" fill="var(--qb-leaf-deep)" />
    </svg>
  )
}

/** Prizes: a gift at a shop counter, with a scanned code. */
export function PrizeArt({ className, ...rest }: Art) {
  return (
    <svg viewBox="0 0 240 180" {...base(className)} {...rest}>
      <ellipse cx="120" cy="152" rx="92" ry="14" fill="var(--qb-sky)" />
      <rect x="40" y="100" width="160" height="44" rx="8" fill="var(--card)" stroke="var(--border)" strokeWidth="2" />
      <path d="M36 100h168l-10-22H46z" fill="var(--qb-leaf-deep)" />
      <path d="M46 78h148l-6 14H52z" fill="var(--qb-leaf)" />
      <rect x="92" y="56" width="56" height="44" rx="6" fill="var(--qb-coin)" />
      <rect x="92" y="56" width="56" height="12" rx="4" fill="var(--qb-coin-deep)" />
      <rect x="116" y="56" width="8" height="44" fill="var(--qb-ink)" opacity=".7" />
      <path d="M120 56c-10-14-24-10-18 0 4 6 12 4 18 0zm0 0c10-14 24-10 18 0-4 6-12 4-18 0z" fill="var(--qb-leaf)" stroke="var(--qb-leaf-deep)" strokeWidth="1.5" />
      <rect x="150" y="110" width="36" height="24" rx="4" fill="var(--background)" stroke="var(--border)" strokeWidth="2" />
      {[154, 160, 165, 171, 176, 181].map((x, i) => (
        <rect key={x} x={x} y="114" width={i % 2 ? 2 : 3} height="16" fill="var(--qb-ink)" />
      ))}
    </svg>
  )
}

/** A passed quiz: a trophy under confetti. */
export function CelebrateArt({ className, ...rest }: Art) {
  return (
    <svg viewBox="0 0 160 140" {...base(className)} {...rest}>
      <ellipse cx="80" cy="124" rx="52" ry="9" fill="var(--qb-sky)" />
      <path d="M50 36h60v22c0 18-13 32-30 32S50 76 50 58z" fill="var(--qb-coin)" />
      <path d="M50 36h60v8H50z" fill="var(--qb-coin-deep)" />
      <path d="M50 44H36c0 16 8 26 20 28M110 44h14c0 16-8 26-20 28" fill="none" stroke="var(--qb-coin-deep)" strokeWidth="4" strokeLinecap="round" />
      <rect x="72" y="90" width="16" height="12" fill="var(--qb-coin-deep)" />
      <rect x="58" y="102" width="44" height="12" rx="4" fill="var(--qb-ink)" opacity=".85" />
      <path d="M72 60l5 5 11-12" fill="none" stroke="var(--qb-ink)" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
      <g>
        <rect x="18" y="14" width="8" height="8" rx="2" fill="var(--qb-leaf)" transform="rotate(20 22 18)" />
        <rect x="132" y="20" width="8" height="8" rx="2" fill="var(--qb-coin)" transform="rotate(-25 136 24)" />
        <circle cx="40" cy="48" r="4" fill="var(--qb-coin)" />
        <circle cx="122" cy="52" r="4" fill="var(--qb-leaf-deep)" />
        <rect x="70" y="8" width="6" height="10" rx="2" fill="var(--qb-leaf-deep)" transform="rotate(35 73 13)" />
        <rect x="98" y="4" width="6" height="10" rx="2" fill="var(--qb-leaf)" transform="rotate(-30 101 9)" />
      </g>
    </svg>
  )
}

/** Nothing here yet: a leaf growing in a pot. */
export function EmptyArt({ className, ...rest }: Art) {
  return (
    <svg viewBox="0 0 160 140" {...base(className)} {...rest}>
      <ellipse cx="80" cy="126" rx="48" ry="8" fill="var(--qb-sky)" />
      <path d="M56 84h48l-6 38H62z" fill="var(--qb-coin-deep)" />
      <rect x="50" y="76" width="60" height="12" rx="4" fill="var(--qb-coin)" />
      <path d="M80 78V44" stroke="var(--qb-leaf-deep)" strokeWidth="4" strokeLinecap="round" />
      <path d="M80 60c-18-2-28-14-30-30 16 0 28 10 30 30z" fill="var(--qb-leaf)" />
      <path d="M80 50c18-2 28-14 30-30-16 0-28 10-30 30z" fill="var(--qb-leaf-deep)" />
    </svg>
  )
}

const PLACEHOLDER_SHAPES = 4

/** A stable number of a key, for choosing a placeholder's shape. */
export function placeholderIndex(key: string): number {
  let hash = 0
  for (const char of key) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  return hash % PLACEHOLDER_SHAPES
}

/** The cover of a publication without one: one of a few patterns, the same for the same publication. */
export function CoverPlaceholder({ seed, className, ...rest }: Art & { seed: string }) {
  const shape = placeholderIndex(seed)
  return (
    <svg viewBox="0 0 160 100" preserveAspectRatio="xMidYMid slice" {...base(className)} {...rest}>
      <rect width="160" height="100" fill="var(--qb-sky)" />
      {shape === 0 && <>
        <circle cx="120" cy="30" r="40" fill="var(--qb-leaf)" opacity=".6" />
        <circle cx="30" cy="90" r="36" fill="var(--qb-leaf-deep)" opacity=".5" />
      </>}
      {shape === 1 && <>
        <path d="M0 70c40-30 80 30 160-10v40H0z" fill="var(--qb-leaf)" opacity=".6" />
        <circle cx="128" cy="28" r="14" fill="var(--qb-coin)" />
      </>}
      {shape === 2 && <>
        <rect x="-20" y="40" width="120" height="120" rx="24" fill="var(--qb-leaf-deep)" opacity=".45" transform="rotate(-20 40 100)" />
        <circle cx="130" cy="34" r="22" fill="var(--qb-leaf)" opacity=".7" />
      </>}
      {shape === 3 && <>
        <path d="M80 10c30 0 60 20 60 50s-30 40-60 40S20 90 20 60s30-50 60-50z" fill="var(--qb-leaf)" opacity=".5" />
        <circle cx="48" cy="40" r="10" fill="var(--qb-coin)" />
      </>}
      <path d="M62 60l10 10 24-26" fill="none" stroke="var(--qb-ink)" strokeWidth="6" strokeLinecap="round" strokeLinejoin="round" opacity=".55" />
    </svg>
  )
}
