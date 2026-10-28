// The iOS shell agrees with the app it opens and with the Android shell (docs/quizbuks/plans/Q49c-ios.md): run with
// `node --test` by the QuizBuks iOS workflow, no dependencies.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const root = new URL('../../../', import.meta.url)
const read = (path) => readFileSync(new URL(path, root), 'utf8')
const config = JSON.parse(read('quizbuks-mobile/ios/capacitor.config.json'))
const twa = JSON.parse(read('quizbuks-mobile/android/twa-manifest.json'))
const swift = read('quizbuks-mobile/ios/xcode/App/App/SceneDelegate.swift')
const constant = (name) => swift.match(new RegExp(`static let ${name} = "([^"]*)"`))?.[1]
const project = read('quizbuks-mobile/ios/xcode/App/App.xcodeproj/project.pbxproj')

test('opens the app\'s own prefix on the Android app\'s host, marked as the iOS shell', () => {
  const start = new URL(config.server.url)
  assert.equal(start.protocol, 'https:')
  assert.equal(start.host, twa.host)
  assert.equal(start.pathname, twa.scope)
  assert.equal(start.searchParams.get('shell'), 'ios')
  assert.equal(constant('host'), twa.host)
  assert.equal(constant('path'), twa.scope)
  assert.equal(constant('shell'), 'ios')
  assert.deepEqual(config.server.allowNavigation, [])
})

test('the page knows the shell\'s user agent; one bundle ID everywhere', () => {
  assert.equal(read('quizbuks-web/app/src/shell.ts').match(/IOS_USER_AGENT = '([^']+)'/)?.[1], config.ios.appendUserAgent)
  assert.equal(config.appId, twa.packageId)
  const ids = [...project.matchAll(/PRODUCT_BUNDLE_IDENTIFIER = ([^;]+);/g)].map((m) => m[1])
  assert.ok(ids.length > 0 && ids.every((id) => id === config.appId), ids.join(', '))
})

test('Universal Links for the host, and only /taker/ in the site association', () => {
  const entitlements = read('quizbuks-mobile/ios/xcode/App/App/App.entitlements')
  assert.deepEqual([...entitlements.matchAll(/<string>applinks:([^<]+)<\/string>/g)].map((m) => m[1]), [twa.host])
  assert.match(read('deploy/quizbuks/apple-app-site-association.sh'), new RegExp(`"/": "${twa.scope}\\*"`))
})

test('iPhone only, iOS 16 and later, portrait', () => {
  const families = [...project.matchAll(/TARGETED_DEVICE_FAMILY = ([^;]+);/g)].map((m) => m[1])
  assert.ok(families.length > 0 && families.every((f) => f === '1'), families.join(', '))
  const targets = [...project.matchAll(/IPHONEOS_DEPLOYMENT_TARGET = ([^;]+);/g)].map((m) => m[1])
  assert.ok(targets.every((t) => t === '16.0'), targets.join(', '))
  const plist = read('quizbuks-mobile/ios/xcode/App/App/Info.plist')
  assert.doesNotMatch(plist, /~ipad/)
  assert.doesNotMatch(plist, /Landscape/)
})
