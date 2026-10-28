// Uploads an Android App Bundle to a Google Play track (docs/quizbuks/plans/Q49b-android.md), with the Play Developer
// API's edits: a service account's key (the secret PLAY_SERVICE_ACCOUNT_JSON, invited in Play Console with release
// rights for this app only) signs a token, the bundle goes into a new edit, the track gets a release of its version
// code, and the edit is committed. No third-party action holds the key. Usage:
//   PLAY_SERVICE_ACCOUNT_JSON=… node play-upload.mjs <package> <bundle.aab> <track> <status>
// <track>: internal | alpha (the closed test) ; <status>: completed, or draft while the app has never been published.
import { createSign } from 'node:crypto'
import { readFileSync } from 'node:fs'

const API = 'https://androidpublisher.googleapis.com'
const SCOPE = 'https://www.googleapis.com/auth/androidpublisher'
const TRACKS = new Set(['internal', 'alpha'])
const STATUSES = new Set(['completed', 'draft'])

/** The OAuth token of the service account (a signed JWT exchanged at its token URI). */
export function assertion(key, nowSeconds) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url')
  const unsigned = `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode({
    iss: key.client_email, scope: SCOPE, aud: key.token_uri, iat: nowSeconds, exp: nowSeconds + 600,
  })}`
  const signature = createSign('RSA-SHA256').update(unsigned).sign(key.private_key).toString('base64url')
  return `${unsigned}.${signature}`
}

/** The arguments, checked before anything is sent. */
export function parseArgs([packageName, bundle, track, status]) {
  if (!/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(packageName ?? '')) throw new Error(`not a package name: ${packageName}`)
  if (!bundle?.endsWith('.aab')) throw new Error(`not an app bundle: ${bundle}`)
  if (!TRACKS.has(track)) throw new Error(`track must be one of ${[...TRACKS].join(', ')}: ${track}`)
  if (!STATUSES.has(status)) throw new Error(`status must be one of ${[...STATUSES].join(', ')}: ${status}`)
  return { packageName, bundle, track, status }
}

async function call(token, method, path, body, contentType = 'application/json') {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, ...(body ? { 'content-type': contentType } : {}) },
    body: body && contentType === 'application/json' ? JSON.stringify(body) : body,
  })
  const text = await response.text()
  // Google's error bodies name what is wrong (a version code used before, an unknown track); no secret is in them.
  if (!response.ok) throw new Error(`${method} ${path.split('?')[0]}: ${response.status} ${text}`)
  return text ? JSON.parse(text) : {}
}

async function main() {
  const { packageName, bundle, track, status } = parseArgs(process.argv.slice(2))
  const key = JSON.parse(process.env.PLAY_SERVICE_ACCOUNT_JSON ?? 'null')
  if (!key?.client_email || !key?.private_key || !key?.token_uri) throw new Error('PLAY_SERVICE_ACCOUNT_JSON is not a service account key')

  const exchanged = await fetch(key.token_uri, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: assertion(key, Math.floor(Date.now() / 1000)),
    }),
  })
  if (!exchanged.ok) throw new Error(`token: ${exchanged.status} ${await exchanged.text()}`)
  const { access_token: token } = await exchanged.json()

  const app = `/androidpublisher/v3/applications/${packageName}`
  const edit = await call(token, 'POST', `${app}/edits`, {})
  const uploaded = await call(token, 'POST',
    `/upload${app}/edits/${edit.id}/bundles?uploadType=media`, readFileSync(bundle), 'application/octet-stream')
  await call(token, 'PUT', `${app}/edits/${edit.id}/tracks/${track}`, {
    track,
    releases: [{ versionCodes: [String(uploaded.versionCode)], status }],
  })
  await call(token, 'POST', `${app}/edits/${edit.id}:commit`)
  console.log(`version ${uploaded.versionCode} of ${packageName} on ${track} (${status})`)
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error.message)
    process.exit(1)
  })
}
