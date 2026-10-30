import { Label, notify, Switch } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { DEFAULT_SETTINGS, readMySettings, saveMySettings, type UserSettings } from './api'
import { QB } from './messages'
import { problemText } from './problems'
import { playSound, setSoundEnabled, soundEnabled, type SoundCue } from './sound'

/** The query of one's own settings (Q57). */
export const SETTINGS_QUERY = ['qb', 'settings'] as const

/**
 * Keeps the sound effects to the user's switch: reads their settings once signed in (the device remembers the last
 * answer meanwhile, so an effect right after opening the app already follows it).
 */
export function useSoundSetting() {
  const settings = useQuery({ queryKey: SETTINGS_QUERY, queryFn: readMySettings, staleTime: Infinity })
  useEffect(() => {
    if (settings.data) setSoundEnabled(settings.data.soundOn)
  }, [settings.data])
  return settings
}

/** The switch of the sound effects, saved to the account at once (Q57; on by default). */
export function SoundSwitch() {
  const { t } = useTranslation(QB)
  const id = useId()
  const queryClient = useQueryClient()
  const settings = useSoundSetting()
  const save = useMutation({
    mutationFn: (soundOn: boolean) => saveMySettings({ soundOn }),
    onMutate: (soundOn) => {
      const before = queryClient.getQueryData<UserSettings>(SETTINGS_QUERY)
      queryClient.setQueryData<UserSettings>(SETTINGS_QUERY, { ...(before ?? DEFAULT_SETTINGS), soundOn })
      setSoundEnabled(soundOn)
      // Turned on: let them hear it.
      if (soundOn) playSound('tap')
      return { before }
    },
    onError: (error, _soundOn, saved) => {
      const before = saved?.before ?? DEFAULT_SETTINGS
      queryClient.setQueryData<UserSettings>(SETTINGS_QUERY, before)
      setSoundEnabled(before.soundOn)
      notify.error(problemText(error))
    },
  })
  const on = settings.data?.soundOn ?? soundEnabled()
  return (
    <div className="flex flex-col gap-1" data-testid="sound-setting">
      <div className="flex items-center justify-between gap-3">
        <Label htmlFor={id}>{t('sound.label')}</Label>
        <Switch id={id} checked={on} disabled={settings.isPending || save.isPending} data-testid="sound-switch"
          aria-describedby={`${id}-hint`} onCheckedChange={(checked) => save.mutate(checked)} />
      </div>
      <p id={`${id}-hint`} className="text-muted-foreground text-sm">{t('sound.hint')}</p>
    </div>
  )
}

/** Plays a cue once, when it is first given (null: nothing yet), e.g. when a result arrives. */
export function useSoundOnce(cue: SoundCue | null) {
  const played = useRef(false)
  useEffect(() => {
    if (cue && !played.current) {
      played.current = true
      playSound(cue)
    }
  }, [cue])
}

/** {@link useSoundOnce} as an element, for a page that decides its cue after its early returns. */
export function SoundOnce({ cue }: { cue: SoundCue | null }) {
  useSoundOnce(cue)
  return null
}
