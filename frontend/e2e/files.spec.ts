import { expect, type Page } from '@playwright/test'
import { adminToken, datasetPath, insert, signIn, test, unique } from './support'

const PRODUCTS = 'urn:jabiz:dataset:default:Product'

/** A PNG drawn by the browser itself: the test needs no image files or libraries. */
async function drawPng(page: Page, width: number, height: number): Promise<Buffer> {
  const base64 = await page.evaluate(
    ([w, h]) => {
      const canvas = document.createElement('canvas')
      canvas.width = w
      canvas.height = h
      const context = canvas.getContext('2d')!
      context.fillStyle = '#c00'
      context.fillRect(0, 0, w / 2, h)
      context.fillStyle = '#00c'
      context.fillRect(w / 2, 0, w / 2, h)
      return canvas.toDataURL('image/png').split(',')[1]
    },
    [width, height],
  )
  return Buffer.from(base64, 'base64')
}

/**
 * ROADMAP phase 13b, requirement 2: a jabiz.file field gets an upload control and a preview from the metadata alone
 * (docs/design/14-files.md section 4); the preview is fetched with the session and shown through an object URL, within
 * the admin frontend's content security policy.
 */
test('a product photo is uploaded from the generated form and shown in the list', async ({ page, request }) => {
  const token = await adminToken(request)
  const code = unique('P')
  await insert(request, token, PRODUCTS, { sku: code, productName: `Product ${code}`, unitPrice: 100, active: true })

  await signIn(page)
  await page.goto(datasetPath(PRODUCTS))
  await page.getByLabel('SKU').first().fill(code)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  const row = page.locator('.ant-table-tbody tr.ant-table-row').first()
  await expect(row).toContainText(`Product ${code}`)
  await row.getByTestId('row-edit').click()

  const drawer = page.locator('.ant-drawer')
  await expect(drawer.getByText('照片')).toBeVisible()
  const png = await drawPng(page, 800, 400)
  await drawer.locator('input[type=file]').setInputFiles({ name: 'shelf.png', mimeType: 'image/png', buffer: png })
  const preview = drawer.getByRole('img', { name: '预览' })
  await expect(preview).toBeVisible()
  await expect(preview).toHaveAttribute('src', /^blob:/)
  await drawer.getByRole('button', { name: /保\s*存/ }).click()
  await expect(drawer).toBeHidden()

  // The list shows the photo as a thumbnail.
  const thumbnail = row.getByRole('img', { name: '预览' })
  await expect(thumbnail).toBeVisible()
  await expect(thumbnail).toHaveAttribute('src', /^blob:/)
})

test('a file the policy does not accept is refused with the server message', async ({ page, request }) => {
  const token = await adminToken(request)
  const code = unique('Q')
  await insert(request, token, PRODUCTS, { sku: code, productName: `Product ${code}`, unitPrice: 100, active: true })

  await signIn(page)
  await page.goto(datasetPath(PRODUCTS))
  await page.getByLabel('SKU').first().fill(code)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  await page.locator('.ant-table-tbody tr.ant-table-row').first().getByTestId('row-edit').click()
  const drawer = page.locator('.ant-drawer')
  // Named like a photo, but markup: the server recognises the content.
  await drawer.locator('input[type=file]').setInputFiles({
    name: 'photo.png',
    mimeType: 'image/png',
    buffer: Buffer.from('<html><script>alert(1)</script></html>'),
  })
  await expect(drawer.getByRole('alert')).toContainText('此处不接受这种类型的文件')
})
