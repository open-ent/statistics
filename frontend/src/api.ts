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
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(String(res.status));
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}

const base = { credentials: 'include' as const };

/** Structures (+ classes) de l'utilisateur (Neo4j, toujours présentes). */
export const getStructures = async (): Promise<StatStructure[]> =>
  json<StatStructure[]>(await fetch(`/stats/structures`, base));

/** Indicateur agrégé pour une structure sur une période (mensuel). */
async function getIndicator<T>(indicator: 'accounts' | 'access', structureId: string, from: string): Promise<T[]> {
  const url = `/stats/list?indicator=${indicator}&from=${from}&frequency=month&entityLevel=structure&entity=${structureId}`;
  return json<T[]>(await fetch(url, base));
}

export const getAccounts = (structureId: string, from: string): Promise<AccountRow[]> =>
  getIndicator<AccountRow>('accounts', structureId, from);

export const getAccess = (structureId: string, from: string): Promise<AccessRow[]> =>
  getIndicator<AccessRow>('access', structureId, from);

export const api = { getStructures, getAccounts, getAccess };
