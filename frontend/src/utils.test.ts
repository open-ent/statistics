import { describe, expect, it } from 'vitest';

import type { AccessRow, AccountRow } from './api';
import { accessByModule, accountsByProfile, profileLabel, yearStart } from './utils';

describe('accessByModule', () => {
  it('agrège par module et trie décroissant', () => {
    const rows: AccessRow[] = [
      { date: '2026-06', profile: '', module: 'Blog', access: 10 },
      { date: '2026-07', profile: '', module: 'Blog', access: 5 },
      { date: '2026-06', profile: '', module: 'Diary', access: 20 },
    ];
    expect(accessByModule(rows)).toEqual([
      { module: 'Diary', total: 20 },
      { module: 'Blog', total: 15 },
    ]);
  });
  it('ignore les lignes sans module', () => {
    expect(accessByModule([{ date: 'x', profile: '', module: '', access: 3 } as AccessRow])).toEqual([]);
  });
});

describe('accountsByProfile', () => {
  it('somme par profil et trie par authentifications', () => {
    const rows: AccountRow[] = [
      { date: '2026-06', profile: 'Teacher', authentications: 95, unique_visitors: 1, activated: 1 },
      { date: '2026-07', profile: 'Teacher', authentications: 492, unique_visitors: 1, activated: 1 },
      { date: '2026-06', profile: 'Student', authentications: 10, unique_visitors: 2, activated: 3 },
    ];
    const out = accountsByProfile(rows);
    expect(out[0]).toEqual({ profile: 'Teacher', authentications: 587, uniqueVisitors: 2, activated: 2 });
    expect(out[1].profile).toBe('Student');
  });
});

describe('profileLabel', () => {
  it('traduit les profils connus', () => {
    expect(profileLabel('Teacher')).toBe('Enseignants');
    expect(profileLabel('Student')).toBe('Élèves');
    expect(profileLabel('Inconnu')).toBe('Inconnu');
  });
});

describe('yearStart', () => {
  it('renvoie le 1er janvier', () => {
    expect(yearStart(new Date(2026, 6, 15))).toBe('2026-01-01');
  });
});
