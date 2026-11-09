import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@jabiz/ui'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { DIFFICULTIES, type Difficulty } from './api'
import { formProblem, ProblemList } from './editing'

export { setQuestionDifficulty, type Difficulty } from './api'

/** The value the selects use for "no difficulty" (a select item cannot have an empty value). */
const NONE = 'NONE'

/** F-S7: a question's difficulty, chosen in the question's dialog (none, easy, medium, hard). */
export function DifficultyField({ value, onChange }: { value: Difficulty | null; onChange: (value: Difficulty | null) => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={`${id}-difficulty`}>{t('difficulty.label')}</Label>
      <Select value={value ?? NONE} onValueChange={(next) => onChange(next === NONE ? null : next as Difficulty)}>
        <SelectTrigger id={`${id}-difficulty`} className="w-48" aria-describedby={`${id}-difficulty-hint`}
          data-testid="question-difficulty">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={NONE}>{t('difficulty.none')}</SelectItem>
          {DIFFICULTIES.map((level) => <SelectItem key={level} value={level}>{t(`difficulty.levels.${level}`)}</SelectItem>)}
        </SelectContent>
      </Select>
      <p id={`${id}-difficulty-hint`} className="text-muted-foreground text-sm">{t('difficulty.hint')}</p>
    </div>
  )
}

/** F-S7: the list's filter by difficulty: every question, those of one level, or those without one. */
export function DifficultyFilter({ value, onChange }: { value: Difficulty | 'NONE' | null
  onChange: (value: Difficulty | 'NONE' | null) => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={`${id}-filter`}>{t('difficulty.filter')}</Label>
      <Select value={value ?? 'ALL'} onValueChange={(next) => onChange(next === 'ALL' ? null : next as Difficulty | 'NONE')}>
        <SelectTrigger id={`${id}-filter`} className="w-40" data-testid="questions-difficulty-filter">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="ALL">{t('difficulty.all')}</SelectItem>
          {DIFFICULTIES.map((level) => <SelectItem key={level} value={level}>{t(`difficulty.levels.${level}`)}</SelectItem>)}
          <SelectItem value={NONE}>{t('difficulty.unset')}</SelectItem>
        </SelectContent>
      </Select>
    </div>
  )
}

/** F-S7: the same difficulty (or none) for every selected question (`QB_QUESTION_DIFFICULTY_SET`). */
export function DifficultyDialog({ count, onClose, onSave, error, pending }: { count: number; onClose: () => void
  onSave: (difficulty: Difficulty | '') => void; error: unknown; pending: boolean }) {
  const { t } = useTranslation(SPONSOR)
  const [value, setValue] = useState<Difficulty | null>('MEDIUM')
  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent data-testid="bank-difficulty-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event) => {
          event.preventDefault()
          onSave(value ?? '')
        }}>
          <DialogHeader>
            <DialogTitle>{t('difficulty.bulkTitle', { count })}</DialogTitle>
            <DialogDescription>{t('difficulty.bulkHint')}</DialogDescription>
          </DialogHeader>
          <DifficultyField value={value} onChange={setValue} />
          <ProblemList problems={formProblem(error, ['difficulty'])} />
          <DialogFooter>
            <Button type="button" variant="outline" disabled={pending} onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="submit" disabled={pending} aria-busy={pending || undefined} data-testid="bank-difficulty-save">
              {t('difficulty.bulkSave')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
