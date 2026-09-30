import { expect } from '@playwright/test'
import { signIn, test } from './support'

/** ROADMAP phase 14b-3: the tasks page is reached from the count in the header. */
test('the header counts the open tasks and leads to them', async ({ page }) => {
  await signIn(page)
  const count = page.getByTestId('task-count')
  await expect(count).toBeVisible()
  await count.click()
  await expect(page).toHaveURL(/\/tasks$/)
  await expect(page.getByText('我的待办').first()).toBeVisible()
})
