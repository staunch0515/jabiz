import { cn } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { differs, lineDiff } from '../../meta/lineDiff'

export interface DiffViewProps {
  /** What the comparison is of (a field's name). */
  label: string
  older: string | null | undefined
  newer: string | null | undefined
  'data-testid'?: string
}

/**
 * The lines of one field compared between two versions: removed lines marked "−", added ones "+", each also named
 * for screen readers (not only by colour).
 */
export default function DiffView({ label, older, newer, 'data-testid': testId }: DiffViewProps) {
  const { t } = useTranslation()
  const lines = lineDiff(older, newer)
  return (
    <div className="flex flex-col gap-1" data-testid={testId}>
      <h4 className="text-sm font-medium">{label}</h4>
      {!differs(lines) ? (
        <p className="text-muted-foreground text-sm">{t('mail.history.same')}</p>
      ) : (
        <ol className="bg-muted/40 overflow-x-auto rounded-md border font-mono text-xs" aria-label={label}>
          {lines.map((line, index) => (
            <li
              key={index}
              data-kind={line.kind}
              className={cn(
                'flex gap-2 px-2 py-0.5 whitespace-pre-wrap',
                line.kind === 'added' && 'bg-success/15',
                line.kind === 'removed' && 'bg-destructive/10',
              )}
            >
              <span aria-hidden className="w-3 shrink-0 select-none">
                {line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' '}
              </span>
              {line.kind !== 'same' && <span className="sr-only">{t(`mail.history.${line.kind}`)}: </span>}
              <span className={cn(line.kind === 'removed' && 'line-through')}>{line.text || ' '}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}
