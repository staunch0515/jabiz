import {
  Alert,
  AlertDescription,
  Button,
  cn,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ImageUp } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { generalProblem } from '../qb/problems'
import { Avatar, InitialAvatar } from './Avatar'
import {
  AVATAR_QUERY, INITIAL, presetUrl, PRESETS, readMyAvatar, setMyAvatar, uploadPhoto, type AvatarState, type MyAvatar,
} from './api'
import './messages'
import { PhotoError, PHOTO_TYPES, squarePhoto } from './photo'

const CHOICES: readonly MyAvatar[] = [INITIAL, ...PRESETS.map((preset) => ({ kind: 'PRESET', preset }) as const)]

function keyOf(avatar: MyAvatar): string {
  return avatar.kind === 'PRESET' ? avatar.preset : avatar.kind === 'PHOTO' ? 'photo' : 'initial'
}

/** A photo made square in the browser, waiting for "Use this photo". */
interface Pending {
  blob: Blob
  url: string
}

/**
 * Choosing one's avatar on "Edit profile" (Q114, docs/quizbuks/plans/impl/Q114.md sections 4.2 and 5.2; the prototype
 * "学者头像原型 · Q114-0"): the avatar at 93 px and "Change avatar", which opens the choices (the initial first, then
 * the presets, four to a row) and, while photos are on (`qb.avatar.photo.enabled`), "Upload a photo". A choice is
 * saved at once; a photo is shown first and saved with "Use this photo". It stays in place and shows why when the
 * save fails. "Remove photo" (asked first) is there whenever the avatar is a photo.
 */
export function AvatarPicker() {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [pending, setPending] = useState<Pending | null>(null)
  const [photoProblem, setPhotoProblem] = useState<string | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const state = useQuery({ queryKey: AVATAR_QUERY, queryFn: readMyAvatar })
  const saved = (avatar: MyAvatar) => queryClient.setQueryData<AvatarState>(AVATAR_QUERY,
    (old) => ({ avatar, photoEnabled: old?.photoEnabled ?? false }))
  const save = useMutation({ mutationFn: setMyAvatar, onSuccess: saved })
  const usePhoto = useMutation({
    mutationFn: async (photo: Blob) => setMyAvatar({ kind: 'PHOTO', photo: await uploadPhoto(photo) }),
    onSuccess: (avatar) => {
      saved(avatar)
      setPending(null)
    },
  })
  const current = keyOf(state.data?.avatar ?? INITIAL)
  const busy = save.isPending || usePhoto.isPending
  const failed = save.isError ? save.error : usePhoto.isError ? usePhoto.error : null
  const problem = photoProblem ?? (failed ? (generalProblem(failed, []) ?? t('avatar.failed')) : undefined)

  // The preview's object URL goes with it.
  useEffect(() => () => {
    if (pending) URL.revokeObjectURL(pending.url)
  }, [pending])

  function reset() {
    save.reset()
    usePhoto.reset()
    setPhotoProblem(null)
    setPending(null)
  }

  async function chosen(file: File | undefined) {
    reset()
    if (!file) return
    try {
      const blob = await squarePhoto(file)
      setPending({ blob, url: URL.createObjectURL(blob) })
    } catch (error) {
      setPhotoProblem(t(`avatar.photo.problems.${error instanceof PhotoError ? error.problem : 'notImage'}`))
    }
  }

  return (
    <div className="flex flex-col items-center gap-3" data-testid="avatar-picker">
      <Avatar className="size-[93px] text-[34px]" data-testid="avatar-current" />
      <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) reset() }}>
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
          {pending ? (
            <div className="flex flex-col items-center gap-3" data-testid="avatar-photo-preview">
              <img src={pending.url} alt={t('avatar.photo.preview')} className="size-32 rounded-full object-cover" />
              <div className="flex gap-2">
                <Button type="button" variant="outline" disabled={busy} onClick={() => reset()}
                  data-testid="avatar-photo-cancel">
                  {t('avatar.photo.cancel')}
                </Button>
                <Button type="button" disabled={busy} onClick={() => usePhoto.mutate(pending.blob)}
                  data-testid="avatar-photo-use">
                  {t('avatar.photo.use')}
                </Button>
              </div>
            </div>
          ) : (
            <div role="group" aria-label={t('avatar.choices')} aria-busy={busy || undefined}
              className="grid grid-cols-4 justify-items-center gap-x-3 gap-y-4" data-testid="avatar-choices">
              {CHOICES.map((choice) => {
                const key = keyOf(choice)
                const isChosen = key === current
                return (
                  <button key={key} type="button" aria-pressed={isChosen} disabled={busy}
                    aria-label={choice.kind === 'PRESET' ? t(`avatar.presets.${choice.preset}`) : t('avatar.initial')}
                    onClick={() => { if (!isChosen) { reset(); save.mutate(choice) } }}
                    className={cn('focus-visible:ring-ring relative size-16 rounded-full outline-none focus-visible:ring-2',
                      'focus-visible:ring-offset-2 disabled:opacity-60',
                      isChosen && 'ring-primary ring-offset-background ring-2 ring-offset-2')}
                    data-testid={`avatar-choice-${key}`}>
                    {choice.kind === 'PRESET'
                      ? <img src={presetUrl(choice.preset)} alt="" className="size-full rounded-full" draggable={false} />
                      : <InitialAvatar className="size-full text-2xl" />}
                    {isChosen && (
                      <span aria-hidden className={cn('bg-primary text-primary-foreground border-background absolute',
                        '-right-1 -bottom-1 flex size-[22px] items-center justify-center rounded-full border-2')}>
                        <Check className="size-3" strokeWidth={3.5} />
                      </span>
                    )}
                  </button>
                )
              })}
            </div>
          )}
          {!pending && (state.data?.photoEnabled || current === 'photo') && (
            <div className="flex flex-wrap items-center justify-center gap-2" data-testid="avatar-photo">
              {state.data?.photoEnabled && (
                <>
                  <input ref={input} type="file" accept={PHOTO_TYPES.join(',')} className="sr-only" tabIndex={-1}
                    aria-hidden data-testid="avatar-photo-input"
                    onChange={(event) => { void chosen(event.target.files?.[0]); event.target.value = '' }} />
                  <Button type="button" variant="outline" disabled={busy} onClick={() => input.current?.click()}
                    data-testid="avatar-photo-upload">
                    <ImageUp aria-hidden />
                    {t('avatar.photo.upload')}
                  </Button>
                </>
              )}
              {current === 'photo' && (
                <ConfirmDialog title={t('avatar.photo.removeTitle')} description={t('avatar.photo.removeText')}
                  confirmLabel={t('avatar.photo.remove')} cancelLabel={t('avatar.photo.cancel')} destructive
                  onConfirm={() => { reset(); return save.mutateAsync(INITIAL) }}
                  trigger={<Button type="button" variant="ghost" disabled={busy} data-testid="avatar-photo-remove">
                    {t('avatar.photo.remove')}</Button>} />
              )}
            </div>
          )}
          {!pending && (state.data?.photoEnabled || current === 'photo') && (
            <p className="text-muted-foreground text-center text-sm" data-testid="avatar-photo-note">
              {t(current === 'photo' ? 'avatar.photo.replaces' : 'avatar.photo.note')}
            </p>
          )}
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
