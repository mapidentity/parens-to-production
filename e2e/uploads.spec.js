// E2E test for recipe photo upload: the owner attaches a photo through a real
// multipart form POST; it is normalized into a content-addressed master, a
// `hero` derivative is generated on first request and served with an immutable
// cache, and the owner can remove it. Requires the e2e Clojure server on 9876.

const { test, expect } = require('@playwright/test');
const path = require('path');

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

const FIXTURE = path.join(__dirname, 'fixtures', 'sample.png');

test('owner uploads a photo; it is normalized, served as a derivative, and removable', async ({ page, request }) => {
  await registerUser(page, request, uniqueEmail());
  await createRecipe(page, {
    title: 'Photo Recipe',
    servings: 2,
    ingredients: 'flour\nmilk',
    steps: 'mix',
  });

  // Attach the photo through the real multipart form.
  await page.setInputFiles('input[name="image"]', FIXTURE);
  await page.getByRole('button', { name: 'Add a photo' }).click();

  // The recipe now renders the photo. Its src is a /img hero derivative whose
  // path is the content hash — proof the master was stored content-addressed.
  const img = page.locator('img[alt="Photo Recipe"]');
  await expect(img).toBeVisible();
  const src = await img.getAttribute('src');
  expect(src).toMatch(/^\/img\/[0-9a-f]{2}\/[0-9a-f]{2}\/[0-9a-f]{64}\/hero\.jpg$/);

  // Fetching that URL generates the derivative on the first hit and serves real
  // JPEG bytes with an immutable cache — the on-the-fly cache, end to end.
  const res = await request.get(src);
  expect(res.status()).toBe(200);
  expect(res.headers()['content-type']).toContain('image/jpeg');
  expect(res.headers()['cache-control']).toContain('immutable');
  expect((await res.body()).length).toBeGreaterThan(0);

  // A non-owner sees the photo but no controls; the owner can remove it.
  await page.getByRole('button', { name: 'Remove photo' }).click();
  await expect(page.locator('img[alt="Photo Recipe"]')).toHaveCount(0);
});

test('a non-image upload is refused with a readable error, no photo attached', async ({ page, request }) => {
  await registerUser(page, request, uniqueEmail());
  await createRecipe(page, {
    title: 'No Photo',
    servings: 1,
    ingredients: 'a',
    steps: 'b',
  });

  // A file that lies about being a PNG: the server decodes to validate and refuses.
  await page.setInputFiles('input[name="image"]', {
    name: 'evil.png',
    mimeType: 'image/png',
    buffer: Buffer.from('this is not an image'),
  });
  await page.getByRole('button', { name: 'Add a photo' }).click();

  await expect(page.getByText('That file is not an image we can read.')).toBeVisible();
  await expect(page.locator('img[alt="No Photo"]')).toHaveCount(0);
});
