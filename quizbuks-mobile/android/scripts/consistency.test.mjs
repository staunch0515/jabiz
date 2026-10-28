// The Android shell agrees with the app it opens (docs/quizbuks/plans/Q49b-android.md): run by the QuizBuks mobile
// workflow with `node --test`, no dependencies. A change to the app's prefix, colours or the shell's package that is
// not made on both sides fails here instead of on a phone.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const root = new URL('../../../', import.meta.url)
const read = (path) => readFileSync(new URL(path, root), 'utf8')
const twa = JSON.parse(read('quizbuks-mobile/android/twa-manifest.json'))

test('opens the app\'s own prefix, marked as the Android shell', () => {
  const spa = read('backend/quizbuks/build.gradle.kts').match(/spa\("([^"]+)", "\.\.\/\.\.\/quizbuks-web\/app"/)
  assert.ok(spa, 'the app\'s spa(...) in backend/quizbuks/build.gradle.kts')
  assert.equal(twa.scope, `${spa[1]}/`)
  const start = new URL(twa.startUrl, `https://${twa.host}`)
  assert.equal(start.pathname, twa.scope)
  assert.equal(start.searchParams.get('shell'), 'android')
})

test('the page knows the shell\'s package', () => {
  const shell = read('quizbuks-web/app/src/shell.ts').match(/ANDROID_PACKAGE = '([^']+)'/)
  assert.equal(shell?.[1], twa.packageId)
})

test('looks like the installed web app', () => {
  const vite = read('quizbuks-web/app/vite.config.ts')
  const colour = (key) => vite.match(new RegExp(`${key}: '(#[0-9a-fA-F]{6})'`))?.[1].toLowerCase()
  assert.equal(twa.themeColor.toLowerCase(), colour('themeColor'))
  assert.equal(twa.backgroundColor.toLowerCase(), colour('backgroundColor'))
  assert.equal(twa.name, vite.match(/name: '([^']+)'/)?.[1])
})

test('the host is the production domain, a plain host name', () => {
  assert.match(twa.host, /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/)
  assert.match(twa.packageId, /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/)
})

test('App Links only for the app\'s prefix, and no notification delegation yet', () => {
  const manifest = read('quizbuks-mobile/android/app/src/main/AndroidManifest.xml')
  const filters = [...manifest.matchAll(/<intent-filter android:autoVerify="true">([\s\S]*?)<\/intent-filter>/g)]
  assert.equal(filters.length, 1)
  assert.match(filters[0][1], /android:host="\$\{hostName\}"/)
  assert.match(filters[0][1], /android:pathPrefix="\$\{scopePath\}"/)
  assert.equal(twa.enableNotifications, false)
  assert.doesNotMatch(manifest, /TRUSTED_WEB_ACTIVITY_SERVICE/)
})
