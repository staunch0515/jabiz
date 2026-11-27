import {
  Alert,
  AlertDescription,
  Button,
  cn,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { generalProblem } from '../qb/problems'
import { Avatar, InitialAvatar } from './Avatar'
import { AVATAR_QUERY, INITIAL, presetUrl, PRESETS, readMyAvatar, setMyAvatar, type MyAvatar } from './api'
import './messages'

const CHOICES: readonly MyAvatar[] = [INITIAL, ...PRESETS.map((preset) => ({ kind: 'PRESET', preset }) as const)]

function keyOf(avatar: MyAvatar): string {
  return avatar.kind === 'PRESET' ? avatar.preset : 'initial'
}

/**
 * Choosing one's avatar on "Edit profile" (Q114a, docs/quizbuks/plans/impl/Q114.md section 4.2; the prototype
 * "学者头像原型 · Q114-0"): the avatar at 93 px and "Change avatar", which opens the choices (the initial first, then
 * the presets, four to a row). A choice is saved at once; it stays in place and shows why when the save fails.
 */
export function AvatarPicker() {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const avatar = useQuery({ queryKey: AVATAR_QUERY, queryFn: readMyAvatar })
  const save = useMutation({
    mutationFn: setMyAvatar,
    onSuccess: (saved) => queryClient.setQueryData(AVATAR_QUERY, saved),
  })
  const current = keyOf(avatar.data ?? INITIAL)
  const problem = save.isError ? (generalProblem(save.error, []) ?? t('avatar.failed')) : undefined

  return (
    <div className="flex flex-col items-center gap-3" data-testid="avatar-picker">
      <Avatar className="size-[93px] text-[34px]" data-testid="avatar-current" />
      <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) save.reset() }}>
        <DialogTrigger asChild>
          <Button type="button" variant="outline" className="border-qb-outline h-9 rounded-full px-4 font-bold"
            data-testid="avatar-change">
            {t('avatar.change')}
          </Button>
        </DialogTrigger>
        <DialogContent data-testid="avatar-dialog">
          <DialogHeader>
            <DialogTitle>{t('avatar.title')}</DialogTitle>
            <DialogDescription>{t('avatar.hint')}</DialogDescription>
          </DialogHeader>
          <div role="group" aria-label={t('avatar.choices')} aria-busy={save.isPending || undefined}
            className="grid grid-cols-4 justify-items-center gap-x-3 gap-y-4" data-testid="avatar-choices">
            {CHOICES.map((choice) => {
              const key = keyOf(choice)
              const chosen = key === current
              return (
                <button key={key} type="button" aria-pressed={chosen} disabled={save.isPending}
                  aria-label={choice.kind === 'PRESET' ? t(`avatar.presets.${choice.preset}`) : t('avatar.initial')}
                  onClick={() => { if (!chosen) save.mutate(choice) }}
                  className={cn('focus-visible:ring-ring relative size-16 rounded-full outline-none focus-visible:ring-2',
                    'focus-visible:ring-offset-2 disabled:opacity-60',
                    chosen && 'ring-primary ring-offset-background ring-2 ring-offset-2')}
                  data-testid={`avatar-choice-${key}`}>
                  {choice.kind === 'PRESET'
                    ? <img src={presetUrl(choice.preset)} alt="" className="size-full rounded-full" draggable={false} />
                    : <InitialAvatar className="size-full text-2xl" />}
                  {chosen && (
                    <span aria-hidden className={cn('bg-primary text-primary-foreground border-background absolute',
                      '-right-1 -bottom-1 flex size-[22px] items-center justify-center rounded-full border-2')}>
                      <Check className="size-3" strokeWidth={3.5} />
                    </span>
                  )}
                </button>
              )
            })}
          </div>
          {problem && (
            <Alert variant="destructive" data-testid="avatar-error">
              <AlertDescription>{problem}</AlertDescription>
            </Alert>
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}
