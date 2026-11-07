import type { MaterialKind } from '../content/api'
import type { AssetKind, TextUsage, UseTarget } from './api'

/**
 * Whether an asset of `kind` (a text with `usage`) may fill a place of `target`: a copy of the server's
 * `AssetRules.suits` (F-C1b), with the same cases in `suits.test.ts`; the two change together. An image for the
 * images, a text of the matching use for an introduction or a description, a prize card for a publication's prize,
 * and for a material the asset kind of the material's kind (`IMAGES` takes an image).
 */
export function suits(target: UseTarget, kind: AssetKind, usage: TextUsage | null,
  materialKind: MaterialKind | null = null): boolean {
  switch (target) {
    case 'MATERIAL':
      return materialKind !== null && kind !== 'TEXT' && kind !== 'PRIZE'
        && kind === (materialKind === 'IMAGES' ? 'IMAGE' : materialKind)
    case 'QUESTION_IMAGE':
    case 'OPTION_IMAGE':
    case 'QUIZ_COVER':
    case 'PUBLICATION_COVER':
      return kind === 'IMAGE'
    case 'QUIZ_INTRO':
      return kind === 'TEXT' && usage === 'QUIZ_INTRO'
    case 'PUBLICATION_REDEEM':
      return kind === 'TEXT' && usage === 'REDEEM_DESCRIPTION'
    case 'PUBLICATION_DIGITAL':
      return kind === 'TEXT' && usage === 'DIGITAL_DESCRIPTION'
    case 'PUBLICATION_REFERRAL':
      return kind === 'TEXT' && usage === 'REFERRAL_DESCRIPTION'
    case 'PUBLICATION_PRIZE':
      return kind === 'PRIZE'
    default:
      return false
  }
}

/** The material kind an asset makes when chosen for a new material (an image makes an images material). */
export function materialKindOf(kind: AssetKind): MaterialKind | null {
  switch (kind) {
    case 'IMAGE':
      return 'IMAGES'
    case 'PDF':
    case 'AUDIO':
    case 'VIDEO_LINK':
    case 'LINK':
    case 'ARTICLE':
      return kind
    default:
      return null
  }
}
