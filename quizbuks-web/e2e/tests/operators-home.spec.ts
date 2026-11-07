import { signInAdmin } from './admin'
import { query } from './content'
import { approvedSponsor } from './publishing'
import { expect, expectAccessible, prepareQuizbuks, signIn, test } from './support'

/*
 * Who looks after whom (F-S1b): the platform's administrators land on the operators' daily check and their reviews;
 * a Sponsor finds their operator, and how to reach it, on the profile's overview.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('the platform lands on the operators’ home; a Sponsor sees their operator (F-S1b)', async ({ page, request }) => {
  const sponsor = await approvedSponsor(request, 'Opal')
  const [operator] = await query(request, sponsor.token, 'qb.sponsor.operator', {})
  expect(operator, 'the Sponsor has an operator').toBeTruthy()

  await signInAdmin(page)
  await expect(page).toHaveURL(/\/admin\/qb\/home$/)
  // A default operator exists (prepareQuizbuks), so the page does not ask for one.
  await expect(page.getByTestId('home-problems')).toBeVisible()
  await expect(page.getByTestId('home-no-default')).toHaveCount(0)
  // Every operator's reviews, a page at a time (the runs add operators: which ones are on the first page varies).
  await expect(page.getByTestId('workload-row').first()).toBeVisible()
  await expectAccessible(page, 'platform home')

  await signIn(page, '/sponsor/', sponsor.email)
  // Signed in (the dashboard) before going to another page, as the other Sponsor tests do.
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.goto('/sponsor/profile')
  const card = page.getByTestId('profile-operator')
  await expect(card.getByTestId('profile-operator-name')).toHaveText(String(operator.name))
  await expect(card.getByTestId('profile-operator-email')).toHaveAttribute('href', `mailto:${operator.contactEmail}`)
  await expectAccessible(page, 'your operator')
})
