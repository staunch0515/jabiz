import type { ElementType } from 'react'
import type { LocalizedText as Text } from '../api/public-queries'
import { useLocale } from '../i18n/locale'
import { pick } from '../lib/localized'

interface Props {
  value: Text | null | undefined
  as?: ElementType
  className?: string
  id?: string
}

/**
 * A multilingual text in the interface language (design section 9.3). When it falls back to another language, the
 * element says which (`lang`), so that screen readers pronounce it correctly and the right font stack applies.
 */
export function LocalizedText({ value, as: Tag = 'span', className, id }: Props) {
  const locale = useLocale()
  const picked = pick(value, locale)
  if (!picked) return null
  return (
    <Tag className={className} id={id} lang={picked.fallback ? picked.lang : undefined}>
      {picked.text}
    </Tag>
  )
}
