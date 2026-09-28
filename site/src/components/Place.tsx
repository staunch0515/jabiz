import type { LocalizedText as Text } from '../api/public-queries'
import { flag } from '../lib/flags'
import { LocalizedText } from './LocalizedText'

interface Props {
  countryCode: string | null | undefined
  name: Text | null | undefined
  area?: Text | null
  className?: string
}

/** A place as a small label (design section 9.2): the flag only at text size, never as the main visual. */
export function Place({ countryCode, name, area, className = 'label' }: Props) {
  if (!name) return null
  const icon = flag(countryCode)
  return (
    <span className={className}>
      {icon ? <span aria-hidden="true">{icon} </span> : null}
      <LocalizedText value={name} />
      {area ? (
        <>
          {' · '}
          <LocalizedText value={area} />
        </>
      ) : null}
    </span>
  )
}
