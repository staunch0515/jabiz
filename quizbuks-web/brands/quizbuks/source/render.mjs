// Renders every PNG of the app icon from the SVG sources here (Q113 §9.5), each drawn at its own size:
// `node quizbuks-web/brands/quizbuks/source/render.mjs` after `pnpm install` in quizbuks-web/e2e, whose Playwright
// it uses (E2E_CHROMIUM picks an installed Chromium, as for the e2e tests). Splash screens and the iOS app icon have
// no alpha channel (App Store Connect refuses an icon with one): they are drawn on white and saved opaque.
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '../../../..')
const { chromium } = createRequire(resolve(root, 'quizbuks-web/e2e/package.json'))('@playwright/test')
const source = (name) => readFileSync(resolve(here, `${name}.svg`), 'utf8')

const ANDROID = 'quizbuks-mobile/android/app/src/main/res'
const IOS = 'quizbuks-mobile/ios/xcode/App/App/Assets.xcassets'
const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 }
// [file, source, size of the art, size of the canvas (the art centred on white) when it is opaque]
const FILES = [
  ['quizbuks-web/brands/quizbuks/public/icon-192.png', 'icon-master', 192],
  ['quizbuks-web/brands/quizbuks/public/icon-512.png', 'icon-master', 512],
  ['quizbuks-web/brands/quizbuks/public/icon-maskable-512.png', 'icon-maskable', 512],
  [`${IOS}/AppIcon.appiconset/AppIcon-512@2x.png`, 'icon-store', 1024, 1024],
  ...['', '-1', '-2'].map((n) => [`${IOS}/Splash.imageset/splash-2732x2732${n}.png`, 'icon-master', 512, 2732]),
  ...Object.entries(DENSITIES).flatMap(([density, k]) => [
    [`${ANDROID}/mipmap-${density}/ic_launcher.png`, 'icon-master', 48 * k],
    [`${ANDROID}/mipmap-${density}/ic_maskable.png`, 'icon-maskable', Math.round(82 * k)],
    [`${ANDROID}/drawable-${density}/splash.png`, 'icon-master', 300 * k, 300 * k],
  ]),
]

const browser = await chromium.launch(process.env.E2E_CHROMIUM ? { executablePath: process.env.E2E_CHROMIUM } : {})
for (const [file, name, size, canvas] of FILES) {
  const side = canvas ?? size
  const page = await browser.newPage({ viewport: { width: side, height: side } })
  const svg = source(name).replace('<svg ', `<svg width="${size}" height="${size}" `)
  await page.setContent(`<body style="margin:0;display:grid;place-items:center;width:${side}px;height:${side}px;`
    + `background:${canvas ? '#fff' : 'transparent'}">${svg}</body>`)
  // Chromium writes a PNG without alpha when every pixel is opaque, as on a white canvas.
  await page.screenshot({ path: resolve(root, file), omitBackground: !canvas })
  await page.close()
  console.log(`${file} (${size}${canvas ? ` on ${canvas}` : ''})`)
}
await browser.close()
