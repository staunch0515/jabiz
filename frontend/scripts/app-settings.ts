/**
 * The application's interface languages and region as the admin frontend is built with them (decision D22 item 7):
 * checked here so that a wrong value fails the build, not the browser. The frontend checks them again at start.
 */
const PLATFORM_LANGUAGES = ['zh', 'ja', 'en']
const REGION = /^[a-z]{2,3}-[A-Z]{2}$/

export function appSettingsProblems(env: Record<string, string | undefined>): string[] {
  const problems: string[] = []
  const languages = env.VITE_JABIZ_LANGUAGES?.trim()
  if (languages) {
    const codes = languages.split(',').map((code) => code.trim()).filter((code) => code !== '')
    const unknown = codes.filter((code) => !PLATFORM_LANGUAGES.includes(code))
    if (unknown.length > 0) problems.push(`VITE_JABIZ_LANGUAGES: ${unknown.join(', ')} not platform languages`)
    if (codes.length === 0) problems.push('VITE_JABIZ_LANGUAGES names no language')
  }
  const region = env.VITE_JABIZ_REGION?.trim()
  if (region && !REGION.test(region)) problems.push(`VITE_JABIZ_REGION must look like en-US, was "${region}"`)
  return problems
}
