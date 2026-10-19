/** Fonctions pures du module Statistiques, testables. */

import type { AccessRow, AccountRow } from './api';
import { dateLocale } from './locale';

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

/**
 * Agrège les comptes par profil, trié par connexions décroissantes. Connexions et visiteurs sont SOMMÉS sur les
 * périodes ; « activés » est un STOCK de comptes (pas un flux) : on retient sa valeur de la période la plus récente
 * (sommer des mois donnerait un total surévalué).
 */
export function accountsByProfile(
  rows: AccountRow[],
): Array<{ profile: string; authentications: number; uniqueVisitors: number; activated: number }> {
  const agg = new Map<string, { authentications: number; uniqueVisitors: number; activated: number; lastDate: string }>();
  for (const r of rows) {
    const p = r.profile || '—';
    const cur = agg.get(p) ?? { authentications: 0, uniqueVisitors: 0, activated: 0, lastDate: '' };
    cur.authentications += r.authentications ?? 0;
    cur.uniqueVisitors += r.unique_visitors ?? 0;
    if (r.activated !== undefined && String(r.date) >= cur.lastDate) {
      cur.activated = r.activated;
      cur.lastDate = String(r.date);
    }
    agg.set(p, cur);
  }
  return [...agg.entries()]
    .map(([profile, v]) => ({ profile, authentications: v.authentications, uniqueVisitors: v.uniqueVisitors, activated: v.activated }))
    .sort((a, b) => b.authentications - a.authentications);
}

/** Libellés FR par défaut des profils ; la traduction vient des clés `stats.profile.<Profil>`. */
export const PROFILE_DEFAULTS: Record<string, string> = {
  Teacher: 'Enseignants',
  Student: 'Élèves',
  Relative: 'Parents',
  Personnel: 'Personnels',
  Guest: 'Invités',
};

/** Libellé FR d'un identifiant de profil ENT (repli quand aucune traduction n'est fournie). */
export function profileLabel(profile: string): string {
  return PROFILE_DEFAULTS[profile] ?? profile;
}

/**
 * Borne `from` par défaut : 12 mois glissants, depuis le 1er du mois de l'an dernier (comme l'AngularJS).
 * Ex. le 18/10/2026 → « 2025-10-01 ». Depuis le 1er janvier, janvier n'aurait montré presque rien.
 */
export function rollingYearStart(d: Date): string {
  return `${d.getFullYear() - 1}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
}

/** Date « YYYY-MM-DD » du 1er janvier de l'année d'une Date. */
export function yearStart(d: Date): string {
  return `${d.getFullYear()}-01-01`;
}

/** Indicateurs clés agrégés (bandeau KPI) à partir des lignes brutes. */
export function kpis(accounts: AccountRow[], access: AccessRow[]): {
  totalAuth: number; totalAccess: number; apps: number; visitors: number;
} {
  const totalAuth = accounts.reduce((s, r) => s + (r.authentications ?? 0), 0);
  const visitors = accounts.reduce((s, r) => s + (r.unique_visitors ?? 0), 0);
  const totalAccess = access.reduce((s, r) => s + (r.access ?? 0), 0);
  const apps = new Set(access.filter((r) => r.module).map((r) => r.module)).size;
  return { totalAuth, totalAccess, apps, visitors };
}

/** Formate un entier avec le séparateur de milliers de la locale de la session. */
export function formatInt(n: number): string {
  return n.toLocaleString(dateLocale());
}

/** Un point d'une série temporelle : période (telle que renvoyée par l'API) et valeur sommée sur tous les profils. */
export interface SeriesPoint {
  date: string;
  value: number;
}

/**
 * Série d'un champ des lignes « accounts » : somme de toutes les lignes d'une même période (tous profils confondus),
 * triée par période croissante. Ex. connexions des enseignants et des élèves de juin additionnées en un point « 2026-06 ».
 */
export function timeSeries(rows: AccountRow[], field: 'authentications' | 'unique_visitors'): SeriesPoint[] {
  const agg = new Map<string, number>();
  for (const r of rows) {
    if (!r.date) continue;
    agg.set(String(r.date), (agg.get(String(r.date)) ?? 0) + (r[field] ?? 0));
  }
  return [...agg.entries()].map(([date, value]) => ({ date, value })).sort((a, b) => a.date.localeCompare(b.date));
}

/**
 * Libellé court d'une période pour l'axe : mois « oct. 2025 », jour « 17/10 », semaine « 2025-W42 » inchangée.
 * Une période non reconnue est renvoyée telle quelle.
 */
export function periodLabel(date: string, locale = 'fr-FR'): string {
  const month = /^(\d{4})-(\d{2})$/.exec(date);
  if (month) return new Date(Number(month[1]), Number(month[2]) - 1, 1).toLocaleDateString(locale, { month: 'short', year: 'numeric' });
  const day = /^(\d{4})-(\d{2})-(\d{2})/.exec(date);
  if (day) return new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3])).toLocaleDateString(locale, { day: '2-digit', month: '2-digit' });
  return date;
}

/** Plus grande valeur d'une série (1 au minimum, pour ne jamais diviser par zéro). */
export const seriesMax = (points: SeriesPoint[]): number => Math.max(1, ...points.map((p) => p.value));
