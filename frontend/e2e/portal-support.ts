import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { adminToken, insert, unique } from './support'

/**
 * The demo's customer portal (backend/app/portal, at /portal/; ROADMAP phase 16c-2): an application's own SPA and PWA
 * signing in to the entry `portal`. The end-to-end server runs with JABIZ_PORTAL_REQUIRE_VERIFIED_EMAIL=false (no mail
 * is sent there), so a customer created by the administrator can sign in and order.
 */
export const PORTAL = '/portal/'

const ROLES = 'urn:jabiz:dataset:platform:SecRole'
const CUSTOMERS = 'urn:jabiz:dataset:default:Customer'
const WAREHOUSES = 'urn:jabiz:dataset:default:Warehouse'
const PRODUCTS = 'urn:jabiz:dataset:default:Product'

export interface Customer {
  userName: string
  password: string
  customerCode: string
  warehouseCode: string
  sku: string
  productName: string
}

/** The role the portal accepts (`CUSTOMER`, with the portal's ordering permission): found, or created once. */
async function customerRole(request: APIRequestContext, token: string): Promise<string> {
  const headers = { Authorization: `Bearer ${token}` }
  const found = await request.post(`/api/datasets/${encodeURIComponent(ROLES)}/query`, {
    headers,
    data: { filters: [{ field: 'roleCode', op: 'eq', value: 'CUSTOMER' }], limit: 1 },
  })
  expect(found.status(), await found.text()).toBe(200)
  const items = (await found.json()).items as { id: string }[]
  if (items.length > 0) return items[0].id
  const role = await insert(request, token, ROLES, { roleCode: 'CUSTOMER', labels: { en: 'Customer' }, enabled: true })
  await insert(request, token, 'urn:jabiz:dataset:platform:SecRolePermission', {
    roleId: role.id, permission: 'commerce.portal.order',
  })
  return role.id
}

/** A customer with an account that may sign in to the portal, and something in stock to order. */
export async function portalCustomer(request: APIRequestContext): Promise<Customer> {
  const token = await adminToken(request)
  const headers = { Authorization: `Bearer ${token}` }
  const roleId = await customerRole(request, token)
  const userName = unique('cust').toLowerCase()
  const password = 'customer-password-1'
  const created = await request.post('/api/processes/SEC_USER_CREATE/latest', {
    headers,
    // An address an administrator set is not verified (decision D36): the portal says so and ordering waits for it.
    data: { userName, displayName: `Customer ${userName}`, password, email: `${userName}@example.com` },
  })
  expect(created.status(), await created.text()).toBe(200)
  const userId = (await created.json()).output.userId
  await insert(request, token, 'urn:jabiz:dataset:platform:SecUserRole', { userId, roleId })
  const customerCode = unique('PC')
  await insert(request, token, CUSTOMERS, { customerCode, customerName: `Customer ${customerCode}`, userId })
  const code = unique('P')
  const productName = `Portal tea ${code}`
  await insert(request, token, WAREHOUSES, { warehouseCode: code, warehouseName: `Warehouse ${code}`, active: true })
  await insert(request, token, PRODUCTS, { sku: code, productName, unitPrice: 300, active: true })
  const received = await request.post('/api/processes/STOCK_RECEIVE/latest', {
    headers,
    data: { warehouseCode: code, sku: code, quantity: 20 },
  })
  expect(received.status(), await received.text()).toBe(200)
  return { userName, password, customerCode, warehouseCode: code, sku: code, productName }
}

/** An order of the customer, placed by the administration (the customer's own needs a verified address). */
export async function placeOrderFor(request: APIRequestContext, customer: Customer, quantity: number) {
  const token = await adminToken(request)
  const placed = await request.post('/api/processes/ORDER_PLACE/latest', {
    headers: { Authorization: `Bearer ${token}` },
    data: { customerCode: customer.customerCode, warehouseCode: customer.warehouseCode,
      lines: [{ sku: customer.sku, quantity }] },
  })
  expect(placed.status(), await placed.text()).toBe(200)
  return (await placed.json()).output.orderNo as string
}

/** Signs in on the portal's own sign-in page and waits for "my orders". */
export async function signInToPortal(page: Page, customer: Customer) {
  await page.goto(`${PORTAL}login`)
  // The portal asks for the address (phase 17m); a user name is taken as well.
  await page.getByTestId('login-user-name').fill(customer.userName)
  await page.getByPlaceholder('密码').fill(customer.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL(`**${PORTAL}orders`)
  await expect(page.getByRole('heading', { name: '我的订单' })).toBeVisible()
}
