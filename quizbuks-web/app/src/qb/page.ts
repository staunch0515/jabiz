/*
 * A sponsor's page (docs/quizbuks/plans/Q60-sponsor-page.md): the app shows it to anybody at `/s/<handle>` once
 * published. Its paths, which both front ends name (a publication's sponsor links to it; the console offers its link);
 * the console's editing is in `sponsor/src/page/`, the app's reading in `app/src/share/`.
 */

/** The app's path of a sponsor's page. */
export function sponsorPagePath(handle: string): string {
  return `/s/${encodeURIComponent(handle)}`
}

/** The link to a sponsor's page anybody can open: the app's page, under this site's /taker/ (Q30). */
export function sponsorPageLink(handle: string, origin: string = window.location.origin): string {
  return `${origin}/taker${sponsorPagePath(handle)}`
}
