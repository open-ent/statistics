import { afterEach, describe, expect, it, vi } from 'vitest';

import { getAccounts, getAccountsHourly } from './api';

afterEach(() => vi.unstubAllGlobals());

function spyFetch() {
  const fn = vi.fn().mockResolvedValue({ ok: true, status: 200, text: async () => '[]' });
  vi.stubGlobal('fetch', fn);
  return fn;
}

describe('niveau d\'entité', () => {
  it('interroge la structure par défaut', async () => {
    const f = spyFetch();
    await getAccounts('S1', '2026-01-01', 'month');
    expect(f.mock.calls[0][0]).toContain('entityLevel=structure&entity=S1');
  });
  it('interroge une classe quand le niveau est « class » (ex. enseignant sur sa classe)', async () => {
    const f = spyFetch();
    await getAccounts('C9', '2026-01-01', 'month', 'class');
    expect(f.mock.calls[0][0]).toContain('entityLevel=class&entity=C9');
    await getAccountsHourly('C9', '2026-01-01', 'class');
    expect(f.mock.calls[1][0]).toContain('frequency=hour&entityLevel=class&entity=C9');
  });
});
