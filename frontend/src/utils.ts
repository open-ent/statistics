/** Fonctions pures du module Statistiques, testables. */

import type { AccessRow, AccountRow } from './api';

/** Agrège les accès par module (somme du champ `access`), trié décroissant. */
export function accessByModule(rows: AccessRow[]): Array<{ module: string; total: number }> {
  const agg = new Map<string, number>();
  for (const r of rows) {
    if (!r.module) continue;
    agg.set(r.module, (agg.get(r.module) ?? 0) + (r.access ?? 0));
  }
  return [...agg.entries()]
    .map(([module, total]) => ({ module, total }))
    .sort((a, b) => b.total - a.total);
}

/** Agrège les comptes par profil (sommes des indicateurs), trié par authentifications décroissantes. */
export function accountsByProfile(
  rows: AccountRow[],
): Array<{ profile: string; authentications: number; uniqueVisitors: number; activated: number }> {
  const agg = new Map<string, { authentications: number; uniqueVisitors: number; activated: number }>();
  for (const r of rows) {
    const p = r.profile || '—';
    const cur = agg.get(p) ?? { authentications: 0, uniqueVisitors: 0, activated: 0 };
    cur.authentications += r.authentications ?? 0;
    cur.uniqueVisitors += r.unique_visitors ?? 0;
    cur.activated += r.activated ?? 0;
    agg.set(p, cur);
  }
  return [...agg.entries()]
    .map(([profile, v]) => ({ profile, ...v }))
    .sort((a, b) => b.authentications - a.authentications);
}

/** Traduit un identifiant de profil ENT en libellé FR. */
export function profileLabel(profile: string): string {
  const map: Record<string, string> = {
    Teacher: 'Enseignants',
    Student: 'Élèves',
    Relative: 'Parents',
    Personnel: 'Personnels',
    Guest: 'Invités',
  };
  return map[profile] ?? profile;
}

/** Date « YYYY-MM-DD » du 1er janvier de l'année d'une Date (borne `from` par défaut). */
export function yearStart(d: Date): string {
  return `${d.getFullYear()}-01-01`;
}
