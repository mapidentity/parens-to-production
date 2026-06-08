// E2E tests for the passwordless auth flow (magic link).
// Requires the e2e Clojure server running on port 9876
// (Playwright's webServer config starts it automatically).

const { test, expect } = require('@playwright/test');

/** Fetch the most recent magic link sent to the given email address. */
async function getMagicLink(request, email) {
  const res = await request.get(`/test/emails?to=${encodeURIComponent(email)}`);
  const emails = await res.json();
  expect(emails.length).toBeGreaterThan(0);
  return emails[emails.length - 1]['magic-link'];
}

/** Generate a unique email for test isolation. */
function uniqueEmail() {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@test.myapp.lan`;
}

/** Register a new user through the full flow (email → magic link → terms → dashboard). */
async function registerUser(page, request, email) {
  await page.goto('/');
  await page.fill('input[name="email"]', email);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();

  const magicLink = await getMagicLink(request, email);
  await page.goto(magicLink);
  await expect(page).toHaveURL(/\/terms\/welcome/);

  await page.getByRole('button', { name: 'Agree and start cooking' }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test('new user registration', async ({ page, request }) => {
  const email = uniqueEmail();

  await page.goto('/');
  await page.fill('input[name="email"]', email);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();

  const magicLink = await getMagicLink(request, email);
  await page.goto(magicLink);

  // New user → redirected to terms acceptance
  await expect(page).toHaveURL(/\/terms\/welcome/);
  await page.getByRole('button', { name: 'Agree and start cooking' }).click();

  // Should reach the dashboard
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(page.getByRole('heading', { name: 'Your recipes' })).toBeVisible();
});

test('returning user login skips terms', async ({ page, request }) => {
  const email = uniqueEmail();
  await registerUser(page, request, email);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.locator('input[name="email"]')).toBeVisible();

  await page.fill('input[name="email"]', email);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();

  const magicLink = await getMagicLink(request, email);
  await page.goto(magicLink);

  // Should go directly to the dashboard (terms already accepted)
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(page.getByRole('heading', { name: 'Your recipes' })).toBeVisible();
});

test('logout prevents dashboard access', async ({ page, request }) => {
  const email = uniqueEmail();
  await registerUser(page, request, email);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.locator('input[name="email"]')).toBeVisible();

  // Direct access to a protected page redirects to home (the sign-in form)
  await page.goto('/dashboard');
  await expect(page.locator('input[name="email"]')).toBeVisible();
});
