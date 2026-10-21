// Client REST du module Statistiques (stats) — session ENT, même origine.
// Incrément 1 : lecture seule (structure + comptes par profil + accès par module).

export interface StatStructure {
  id: string;
  name: string;
  classes?: Array<{ id: string; name: string }>;
}

/** Ligne d'indicateur « accounts » (comptes/authentifications par profil et période). */
export interface AccountRow {
  date: string;
  profile: string;
  authentications?: number;
  /** Type d'appareil, seulement quand la requête porte `device=true` (desktop, smartphone, mobile_app, other). */
  device_type?: string;
  unique_visitors?: number;
  activated?: number;
  activations?: number;
  loaded?: number;
}

/** Ligne d'indicateur « access » (accès par module, profil et période). */
export interface AccessRow {
  date: string;
  profile: string;
  module: string;
  access?: number;
  unique_access?: number;
  /** `ACCESS` (application) ou `CONNECTOR` (connecteur externe) — comme les filtres de l'AngularJS. */
  type?: string;
}

/** Erreur HTTP typée : le code distingue « pas de droit » (401/403) d'une panne. */
export class HttpError extends Error {
  constructor(public status: number) {
    super(String(status));
  }
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new HttpError(res.status);
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}

const base = { credentials: 'include' as const };

/** Structures (+ classes) de l'utilisateur (Neo4j, toujours présentes). */
export const getStructures = async (): Promise<StatStructure[]> =>
  json<StatStructure[]>(await fetch(`/stats/structures`, base));

/** Granularité d'agrégation des statistiques. */
export type Frequency = 'day' | 'week' | 'month';

/** Indicateur agrégé pour une structure sur une période, à la granularité `frequency`. */
async function getIndicator<T>(indicator: 'accounts' | 'access', structureId: string, from: string, frequency: Frequency, device = false): Promise<T[]> {
  const url = `/stats/list?indicator=${indicator}&from=${from}&frequency=${frequency}&entityLevel=structure&entity=${structureId}${device ? '&device=true' : ''}`;
  return json<T[]>(await fetch(url, base));
}

export const getAccounts = (structureId: string, from: string, frequency: Frequency = 'month'): Promise<AccountRow[]> =>
  getIndicator<AccountRow>('accounts', structureId, from, frequency);

/** Connexions par type d'appareil (`device=true`) : une ligne par période, profil et appareil. */
export const getAccountsByDevice = (structureId: string, from: string, frequency: Frequency = 'month'): Promise<AccountRow[]> =>
  getIndicator<AccountRow>('accounts', structureId, from, frequency, true);

export const getAccess = (structureId: string, from: string, frequency: Frequency = 'month'): Promise<AccessRow[]> =>
  getIndicator<AccessRow>('access', structureId, from, frequency);

/** Modes d'export CSV (mêmes que l'AngularJS : `export.service.ts`). */
export type ExportMode = 'aggregated' | 'substructures' | 'structure' | 'classes';

/**
 * URL d'export CSV d'un indicateur, avec les paramètres attendus par `GET /stats/export` (cf. `StatsController`).
 * Ex. mode « classes » → `…&entityLevel=structure&entity=<id>&structureClasses=true`.
 */
export function exportUrl(indicator: 'accounts' | 'access', mode: ExportMode, structureId: string, from: string, frequency: Frequency): string {
  const params = new URLSearchParams({ indicator, from, frequency, entityLevel: 'structure', entity: structureId });
  if (mode === 'aggregated') params.set('accumulate', 'true');
  if (mode === 'substructures') params.set('substructures', 'true');
  if (mode === 'classes') params.set('structureClasses', 'true');
  return `/stats/export?${params.toString()}`;
}

export const api = { getStructures, getAccounts, getAccess, getAccountsByDevice };
