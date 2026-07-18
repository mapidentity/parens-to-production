// The client-error beacon: an exception in an island must reach the
// operator's log, not die in the visitor's console.

const { test, expect } = require('@playwright/test');

test('a client-side error is beaconed to the server', async ({ page }) => {
  await page.goto('/');
  const beacon = page.waitForRequest(
    (r) => r.url().includes('/client-error') && r.method() === 'POST'
  );
  // A real thrown error would fail the test runner; dispatching the event
  // exercises the same listener with a payload we control.
  await page.evaluate(() => {
    window.dispatchEvent(new ErrorEvent('error', {
      message: 'e2e synthetic error',
      filename: 'e2e.js',
      lineno: 1,
    }));
  });
  const request = await beacon;
  expect(request.postData()).toContain('e2e synthetic error');
});
