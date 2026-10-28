// The upload script's checks and its token, offline (the upload itself needs Play: the workflow's manual run).
import assert from 'node:assert/strict'
import { createVerify, generateKeyPairSync } from 'node:crypto'
import { test } from 'node:test'
import { assertion, parseArgs } from './play-upload.mjs'

test('takes only a package, a bundle, a known track and status', () => {
  assert.deepEqual(parseArgs(['com.quizbuks.app', 'app.aab', 'alpha', 'completed']),
    { packageName: 'com.quizbuks.app', bundle: 'app.aab', track: 'alpha', status: 'completed' })
  assert.throws(() => parseArgs(['com.quizbuks.app', 'app.apk', 'alpha', 'completed']), /app bundle/)
  assert.throws(() => parseArgs(['com.quizbuks.app', 'app.aab', 'production', 'completed']), /track/)
  assert.throws(() => parseArgs(['com.quizbuks.app', 'app.aab', 'internal', 'inProgress']), /status/)
  assert.throws(() => parseArgs(['com.quizbuks.app/../x', 'app.aab', 'internal', 'draft']), /package/)
})

test('signs a short-lived token for the publisher scope only', () => {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const key = {
    client_email: 'upload@example.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    token_uri: 'https://oauth2.googleapis.com/token',
  }
  const [header, claims, signature] = assertion(key, 1_000).split('.')
  assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'RS256', typ: 'JWT' })
  assert.deepEqual(JSON.parse(Buffer.from(claims, 'base64url')), {
    iss: key.client_email, scope: 'https://www.googleapis.com/auth/androidpublisher', aud: key.token_uri,
    iat: 1_000, exp: 1_600,
  })
  assert.ok(createVerify('RSA-SHA256').update(`${header}.${claims}`).verify(publicKey, Buffer.from(signature, 'base64url')))
})
