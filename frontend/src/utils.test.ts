import { describe, expect, it } from 'vitest';

import type { AccessRow, AccountRow } from './api';
import { exportUrl } from './api';
import { accessByModule, accountsByProfile, formatInt, kpis, profileLabel, rollingYearStart, yearStart } from './utils';

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
    // « activés » = stock : valeur de la période la plus récente (1), pas la somme des périodes.
    expect(out[0]).toEqual({ profile: 'Teacher', authentications: 587, uniqueVisitors: 2, activated: 1 });
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

describe('kpis', () => {
  it('agrège connexions, visiteurs, accès et applications distinctes', () => {
    const accounts: AccountRow[] = [
      { date: '2026-06', profile: 'Teacher', authentications: 95, unique_visitors: 1 },
      { date: '2026-07', profile: 'Teacher', authentications: 492, unique_visitors: 1 },
    ];
    const access: AccessRow[] = [
      { date: '2026-06', profile: '', module: 'Blog', access: 40 },
      { date: '2026-06', profile: '', module: 'Diary', access: 97 },
      { date: '2026-07', profile: '', module: 'Blog', access: 3 },
    ];
    expect(kpis(accounts, access)).toEqual({ totalAuth: 587, totalAccess: 140, apps: 2, visitors: 2 });
  });
});

describe('formatInt', () => {
  it('formate avec séparateur de milliers FR', () => {
    expect(formatInt(1234).replace(/ | /g, ' ')).toBe('1 234');
    expect(formatInt(7)).toBe('7');
  });
});

describe('rollingYearStart', () => {
  it('12 mois glissants depuis le 1er du mois de l\'an dernier', () => {
    expect(rollingYearStart(new Date(2026, 9, 18))).toBe('2025-10-01');
    expect(rollingYearStart(new Date(2026, 0, 5))).toBe('2025-01-01');
  });
});

describe('exportUrl', () => {
  it('établissement seul', () => {
    const u = new URL('http://x' + exportUrl('accounts', 'structure', 's1', '2025-10-01', 'month'));
    expect(Object.fromEntries(u.searchParams)).toEqual({ indicator: 'accounts', from: '2025-10-01', frequency: 'month', entityLevel: 'structure', entity: 's1' });
  });
  it('agrégé, sous-structures et classes ajoutent leur drapeau', () => {
    expect(exportUrl('access', 'aggregated', 's1', 'f', 'day')).toContain('accumulate=true');
    expect(exportUrl('access', 'substructures', 's1', 'f', 'day')).toContain('substructures=true');
    expect(exportUrl('access', 'classes', 's1', 'f', 'day')).toContain('structureClasses=true');
  });
});
