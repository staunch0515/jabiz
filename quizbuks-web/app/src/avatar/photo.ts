/*
 * Making a photo into an avatar in the browser (Q114b, docs/quizbuks/plans/impl/Q114.md section 5.2): the middle
 * square of the picture, at most `PHOTO_SIZE` pixels a side, as a JPEG. A phone's photo of several megabytes thus fits
 * the policy's 2 MB, and the server re-encodes it anyway (without its metadata). No cropping frame (section 5.3).
 */

/** The side of the square uploaded, in pixels. */
export const PHOTO_SIZE = 512
/** The largest file the page tries to read; a larger one is refused before it is decoded. */
export const PHOTO_MAX_SOURCE_BYTES = 20 * 1024 * 1024
/** The kinds of file the policy accepts (`qb.taker-avatar`). */
export const PHOTO_TYPES = ['image/jpeg', 'image/png'] as const

/** Why a photo was not made into an avatar: the message key under `avatar.photo.problems`. */
export type PhotoProblem = 'notImage' | 'tooLarge'

export class PhotoError extends Error {
  readonly problem: PhotoProblem

  constructor(problem: PhotoProblem) {
    super(problem)
    this.problem = problem
  }
}

/** The part of a `width` × `height` picture that is kept (its middle square) and the side it is drawn at. */
export function squareCrop(width: number, height: number, max = PHOTO_SIZE) {
  const side = Math.min(width, height)
  return {
    sx: Math.floor((width - side) / 2),
    sy: Math.floor((height - side) / 2),
    side,
    size: Math.max(1, Math.min(max, side)),
  }
}

/** The kinds a file may be, by its type (the server judges by the content). */
export function photoProblem(file: Blob): PhotoProblem | null {
  if (!(PHOTO_TYPES as readonly string[]).includes(file.type)) return 'notImage'
  if (file.size > PHOTO_MAX_SOURCE_BYTES) return 'tooLarge'
  return null
}

/** The middle square of `file`, as a JPEG of at most `PHOTO_SIZE` pixels a side. */
export async function squarePhoto(file: Blob): Promise<Blob> {
  const problem = photoProblem(file)
  if (problem) throw new PhotoError(problem)
  let bitmap: ImageBitmap
  try {
    // The orientation the camera wrote is applied, so a portrait photo is not turned on its side.
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    throw new PhotoError('notImage')
  }
  try {
    const crop = squareCrop(bitmap.width, bitmap.height)
    const canvas = document.createElement('canvas')
    canvas.width = crop.size
    canvas.height = crop.size
    const context = canvas.getContext('2d')
    if (!context) throw new PhotoError('notImage')
    context.drawImage(bitmap, crop.sx, crop.sy, crop.side, crop.side, 0, 0, crop.size, crop.size)
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.9))
    if (!blob) throw new PhotoError('notImage')
    return blob
  } finally {
    bitmap.close()
  }
}
