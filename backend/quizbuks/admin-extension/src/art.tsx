/*
 * Small drawings for the administration's empty states (Q53c), inline SVG, decorative (`aria-hidden`), in the theme's
 * neutral tokens only so that they follow light and dark (the back office keeps the platform's colours; the sponsor
 * console's brand drawings are not reused). Each is 96×72.
 */

export type ArtKind = 'inbox' | 'done' | 'content' | 'money' | 'people' | 'search'

const frame = 'stroke-muted-foreground/60'
const fill = 'fill-muted'
const accent = 'fill-primary/15 stroke-primary/50'

/** One of the drawings, by kind. */
export function EmptyArt({ kind, className }: { kind: ArtKind; className?: string }) {
  return (
    <svg viewBox="0 0 96 72" aria-hidden className={className ?? 'h-16 w-20'} data-art={kind} fill="none"
      strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      {kind === 'inbox' && (
        <>
          <path d="M16 40 L26 16 H70 L80 40 V58 H16 Z" className={`${fill} ${frame}`} />
          <path d="M16 40 H36 L40 48 H56 L60 40 H80" className={frame} />
        </>
      )}
      {kind === 'done' && (
        <>
          <circle cx="48" cy="36" r="24" className={accent} />
          <path d="M37 37 L45 45 L60 29" className="stroke-primary" strokeWidth={3} />
        </>
      )}
      {kind === 'content' && (
        <>
          <rect x="22" y="10" width="44" height="54" rx="4" className={`${fill} ${frame}`} />
          <path d="M30 24 H58 M30 34 H58 M30 44 H48" className={frame} />
          <circle cx="68" cy="52" r="10" className={accent} />
          <path d="M68 47 V57 M63 52 H73" className="stroke-primary" />
        </>
      )}
      {kind === 'money' && (
        <>
          <rect x="14" y="20" width="58" height="34" rx="4" className={`${fill} ${frame}`} />
          <circle cx="43" cy="37" r="8" className={frame} />
          <circle cx="72" cy="22" r="11" className={accent} />
          <path d="M72 16 V28" className="stroke-primary" />
        </>
      )}
      {kind === 'people' && (
        <>
          <circle cx="38" cy="26" r="9" className={`${fill} ${frame}`} />
          <path d="M22 58 C22 46 30 40 38 40 C46 40 54 46 54 58" className={`${fill} ${frame}`} />
          <circle cx="62" cy="30" r="7" className={accent} />
          <path d="M50 58 C50 49 56 44 62 44 C68 44 74 49 74 58" className={accent} />
        </>
      )}
      {kind === 'search' && (
        <>
          <circle cx="42" cy="32" r="16" className={`${fill} ${frame}`} />
          <path d="M54 44 L68 58" className="stroke-primary" strokeWidth={4} />
        </>
      )}
    </svg>
  )
}
