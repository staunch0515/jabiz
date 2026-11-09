import {
  copyPublication,
  formOf,
  inputOf,
  readConditions,
  readPublication,
  saveConditions,
  savePublication,
  type Publication,
  type SaveOutput,
} from './api'
import { formOfSettings, rescaleForm, settingsOf, type Placement, type PublicationSettings } from './settings'

/** A copy or an import: the draft as saved, and whether its scores were rescaled to another full score. */
export interface Placed {
  output: SaveOutput
  /** The full scores the pass mark and tiers were rescaled from and to; null when they were kept. */
  rescaled: { from: number; to: number } | null
}

/**
 * Rescales a new draft's pass mark and tiers when the full score it got (the server's, which counts a pick of
 * questions) is not the one they were set for, and saves it again. A form typed wrong after rescaling cannot happen
 * (whole numbers stay whole); the server checks the rest and lists it as problems.
 */
async function rescaled(output: SaveOutput, from: number | null): Promise<Placed> {
  const to = output.fullScore
  if (!from || !to || from === to) return { output, rescaled: null }
  const detail = await readPublication(output.publicationId)
  if (!detail) return { output, rescaled: null }
  const built = inputOf(rescaleForm(formOf(detail), from, to), output.publicationId, output.revision)
  if (!built.input) return { output, rescaled: null }
  return { output: await savePublication(built.input), rescaled: { from, to } }
}

/**
 * Copies a publication onto the version, times and name chosen (Q102). The server copies its policy, tiers,
 * and conditions (`QB_PUBLICATION_COPY`); onto another full score the scores are then rescaled.
 */
export async function copyOnto(publication: Publication, placement: Omit<Placement, 'fullScore'>): Promise<Placed> {
  const copied = await copyPublication(publication.publicationId, placement.name, {
    versionId: placement.versionId === publication.versionId ? null : placement.versionId,
    startAt: placement.startAt,
    endAt: placement.endAt,
  })
  return rescaled(copied, publication.fullScore)
}

/**
 * A new draft from a settings file (Q102), saved as one typed by hand would be: `QB_PUBLICATION_SAVE`, rescaled when
 * the version's full score differs, then its matching conditions (`QB_PUBLICATION_AUDIENCE_SAVE`). A refusal of the
 * conditions leaves the draft saved; it is reported, not thrown.
 */
export async function importSettings(settings: PublicationSettings, placement: Omit<Placement, 'fullScore'>):
  Promise<Placed & { conditionsFailed: unknown }> {
  const built = inputOf(formOfSettings(settings, { ...placement, fullScore: null }), null, null)
  // The form is made of whole numbers only, so it always builds; the guard keeps the types honest.
  if (!built.input) throw new Error('The settings did not make a draft')
  const placed = await rescaled(await savePublication(built.input), settings.fullScore)
  if (settings.audience === 'INVITED' || settings.conditions.length === 0) return { ...placed, conditionsFailed: null }
  try {
    const out = await saveConditions(placed.output.publicationId, placed.output.revision, settings.conditions)
    return { output: { ...placed.output, revision: out.revision }, rescaled: placed.rescaled, conditionsFailed: null }
  } catch (error) {
    return { ...placed, conditionsFailed: error }
  }
}

/** The settings of a publication, read with its matching conditions. */
export async function exportSettings(publicationId: string): Promise<PublicationSettings | null> {
  const detail = await readPublication(publicationId)
  if (!detail) return null
  const conditions = detail.publication.audience === 'INVITED' ? [] : await readConditions(publicationId)
  return settingsOf(detail, conditions)
}
