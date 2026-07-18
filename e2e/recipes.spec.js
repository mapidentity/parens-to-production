// E2E tests for the recipe-versioning flow: create, edit (→ history + diff),
// and fork (→ lineage). Requires the e2e Clojure server on port 9876.

const { test, expect } = require('@playwright/test');

async function getMagicLink(request, email) {
  const res = await request.get(`/test/emails?to=${encodeURIComponent(email)}`);
  const emails = await res.json();
  expect(emails.length).toBeGreaterThan(0);
  return emails[emails.length - 1]['magic-link'];
}

function uniqueEmail() {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@test.myapp.lan`;
}

async function registerUser(page, request, email) {
  await page.goto('/');
  await page.fill('input[name="email"]', email);
  await page.getByRole('button', { name: 'Sign in' }).click();
  const magicLink = await getMagicLink(request, email);
  await page.goto(magicLink);
  await page.getByRole('button', { name: 'Agree and start cooking' }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

async function createRecipe(page, { title, servings, ingredients, steps }) {
  await page.goto('/recipes/new');
  await page.fill('input[name="title"]', title);
  await page.fill('input[name="servings"]', String(servings));
  await page.fill('textarea[name="ingredients"]', ingredients);
  await page.fill('textarea[name="steps"]', steps);
  await page.getByRole('button', { name: 'Create recipe' }).click();
  await expect(page).toHaveURL(/\/recipes\/[0-9a-f-]{36}$/);
}

test('create a recipe and see it rendered', async ({ page, request }) => {
  await registerUser(page, request, uniqueEmail());
  await createRecipe(page, {
    title: 'Test Pancakes',
    servings: 3,
    ingredients: 'flour\nmilk\neggs',
    steps: 'mix\nfry',
  });
  await expect(page.getByRole('heading', { name: 'Test Pancakes' })).toBeVisible();
  await expect(page.getByText('flour')).toBeVisible();
});

test('editing a recipe records a new version with a diff', async ({ page, request }) => {
  await registerUser(page, request, uniqueEmail());
  await createRecipe(page, {
    title: 'Edit Me',
    servings: 2,
    ingredients: 'flour\nmilk',
    steps: 'mix',
  });

  // Edit: add an ingredient line. Edit/Delete live in the owner-actions popover
  // menu (Layer 1), so open it via the "Actions" trigger before clicking Edit.
  await page.getByRole('button', { name: 'Actions' }).click();
  await page.getByRole('link', { name: 'Edit' }).click();
  await page.fill('textarea[name="ingredients"]', 'flour\nmilk\nvanilla');
  await page.getByRole('button', { name: 'Save recipe' }).click();
  await expect(page).toHaveURL(/\/recipes\/[0-9a-f-]{36}$/);

  // History shows two versions
  await page.getByRole('link', { name: /Version history/ }).click();
  await expect(page.getByText('Version 1')).toBeVisible();
  await expect(page.getByText('Version 2')).toBeVisible();

  // Diff from previous shows the added line
  await page.getByRole('link', { name: 'Changes from previous' }).click();
  await expect(page.locator('.diff-add', { hasText: 'vanilla' })).toBeVisible();
});

test('forking a recipe records lineage', async ({ page, request }) => {
  await registerUser(page, request, uniqueEmail());
  await createRecipe(page, {
    title: 'Original Loaf',
    servings: 1,
    ingredients: 'flour\nwater\nsalt',
    steps: 'knead\nbake',
  });

  await page.getByRole('button', { name: 'Fork this recipe' }).click();
  await expect(page).toHaveURL(/\/recipes\/[0-9a-f-]{36}$/);

  // The fork descends from exactly one ancestor, shown in the lineage trail,
  // which links back to the parent recipe.
  await expect(page.getByText('Descends from 1 ancestor')).toBeVisible();
  await expect(page.locator('a', { hasText: 'Original Loaf' }).first()).toBeVisible();
});

test('an invalid submit re-renders the form with errors and preserved input', async ({ page, request }) => {
  await registerUser(page, request, uniqueEmail());
  await page.goto('/recipes/new');
  // A whitespace-only title slips through the HTML `required` courtesy layer;
  // the server is the validator of record.
  await page.fill('input[name="title"]', '   ');
  await page.fill('input[name="servings"]', '4');
  await page.fill('textarea[name="ingredients"]', 'typed and kept');
  // Plant a marker a real navigation would wipe — the morph must not.
  await page.evaluate(() => { window.__stillHere = true; });
  await page.getByRole('button', { name: 'Create recipe' }).click();

  // The server answered 422 and the dispatcher morphed the same form back in
  // place: error line visible, URL unchanged, typed input intact.
  await expect(page.getByText('Give the recipe a title.')).toBeVisible();
  await expect(page).toHaveURL(/\/recipes\/new$/);
  await expect(page.locator('textarea[name="ingredients"]')).toHaveValue('typed and kept');
  // In place, not a navigation: the page's JS world survived…
  expect(await page.evaluate(() => window.__stillHere)).toBe(true);
  // …and focus moved to the first invalid field, whose aria-describedby
  // announces the error.
  await expect(page.locator('input[name="title"]')).toBeFocused();

  // Fixing the field completes on the same form.
  await page.fill('input[name="title"]', 'Recovered Recipe');
  await page.getByRole('button', { name: 'Create recipe' }).click();
  await expect(page).toHaveURL(/\/recipes\/[0-9a-f-]{36}$/);
});
