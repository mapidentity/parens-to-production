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
  // Block until the confirmation renders: that is the guarantee that the
  // sign-in POST has been handled and the stubbed email captured — the
  // precondition getMagicLink's single, retry-free fetch relies on.
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
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
  // The commit message: stored on the transaction entity itself.
  await page.fill('input[name="note"]', 'Vanilla makes it breakfast');
  await page.getByRole('button', { name: 'Save recipe' }).click();
  await expect(page).toHaveURL(/\/recipes\/[0-9a-f-]{36}$/);

  // History shows two versions, the edit carrying its author and note
  await page.getByRole('link', { name: /Version history/ }).click();
  await expect(page.getByText('Version 1')).toBeVisible();
  await expect(page.getByText('Version 2')).toBeVisible();
  await expect(page.getByText('Vanilla makes it breakfast')).toBeVisible();

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

test('the preview pane renders unsaved edits, server-side', async ({ page, request }) => {
  await registerUser(page, request, uniqueEmail());
  await createRecipe(page, {
    title: 'Preview Base',
    servings: 2,
    ingredients: 'one thing',
    steps: 'do it',
  });
  await page.getByRole('button', { name: 'Actions' }).click();
  await page.getByRole('link', { name: 'Edit' }).click();

  // The edit page's pane is server-rendered before any keystroke.
  const pane = page.locator('#recipe-preview');
  await expect(pane).toContainText('Preview Base');

  // Type an unsaved markdown edit: the island debounces, POSTs the form to
  // the preview endpoint, and morphs back HTML rendered from a d/with
  // database — real markdown pipeline included.
  await page.fill('textarea[name="description"]', 'A **speculative** delight');
  await expect(pane.locator('strong')).toHaveText('speculative');

  // Leave without saving: the recipe is untouched.
  await page.goto('/recipes');
  await page.getByRole('link', { name: 'Preview Base' }).click();
  await expect(page.getByText('speculative')).toHaveCount(0);
});

test('search finds a recipe by title word', async ({ page, request }) => {
  await registerUser(page, request, uniqueEmail());
  const title = `Sesame Noodles ${Date.now()}`;
  await createRecipe(page, { title, servings: 2, ingredients: 'noodles', steps: 'toss' });

  // A plain GET with the query in the URL — addressable by construction.
  await page.goto('/search?q=sesame');
  await expect(page.getByRole('link', { name: new RegExp(title) })).toBeVisible();
});

test('the dashboard reports forks that happened while you were away', async ({ page, request }) => {
  // Alice creates; Bob forks; Alice returns to news.
  const alice = uniqueEmail();
  const title = `Fork Me Focaccia ${Date.now()}`;
  await registerUser(page, request, alice);
  await createRecipe(page, { title, servings: 2, ingredients: 'flour', steps: 'bake' });
  const recipeUrl = page.url();
  // Sign-out is dispatcher-enhanced: the cross-layout response triggers a
  // real navigation (location.assign) after the click resolves. Wait for it
  // to land, or the next page.goto races it and gets aborted.
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.waitForURL('/');

  await registerUser(page, request, uniqueEmail());
  await page.goto(recipeUrl);
  await page.getByRole('button', { name: 'Fork this recipe' }).click();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.waitForURL('/');

  // Alice signs back in: the activity panel leads the dashboard —
  // computed from the transaction log, no notification machinery anywhere.
  await page.fill('input[name="email"]', alice);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
  const magicLink = await getMagicLink(request, alice);
  await page.goto(magicLink);
  await expect(page.getByText('While you were away')).toBeVisible();
  await expect(page.getByText('forked your recipe:')).toBeVisible();

  // Refresh: the cursor advanced; the news is folded into history.
  await page.reload();
  await expect(page.getByText('While you were away')).toHaveCount(0);
});
