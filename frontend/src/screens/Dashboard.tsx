import { useEdificeClient } from '@open-ent/react';
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../api';
import { accessByModule, accountsByProfile, profileLabel, yearStart } from '../utils';

/** Tableau de bord Statistiques : établissement + comptes par profil + accès par module. */
export function Dashboard() {
  const { t } = useTranslation(['stats', 'common']);
  const { user, init } = useEdificeClient();
  const structureId = user?.structures?.[0] ?? '';
  const from = yearStart(new Date());

  const structuresQuery = useQuery({ queryKey: ['stats', 'structures'], queryFn: () => api.getStructures() });
  const accountsQuery = useQuery({ queryKey: ['stats', 'accounts', structureId, from], queryFn: () => api.getAccounts(structureId, from), enabled: !!structureId });
  const accessQuery = useQuery({ queryKey: ['stats', 'access', structureId, from], queryFn: () => api.getAccess(structureId, from), enabled: !!structureId });

  const structure = (structuresQuery.data ?? []).find((s) => s.id === structureId) ?? (structuresQuery.data ?? [])[0];
  const profiles = useMemo(() => accountsByProfile(accountsQuery.data ?? []), [accountsQuery.data]);
  const modules = useMemo(() => accessByModule(accessQuery.data ?? []), [accessQuery.data]);
  const maxAccess = modules[0]?.total ?? 0;

  if (init && !structureId) {
    return (
      <div>
        <h1>{t('stats.title', { defaultValue: 'Statistiques' })}</h1>
        <div className="alert alert-info" role="alert">
          {t('stats.no.structure', { defaultValue: 'Aucun établissement associé à votre compte.' })}
        </div>
      </div>
    );
  }

  return (
    <div>
      <h1 className="mb-8">{t('stats.title', { defaultValue: 'Statistiques' })}</h1>
      {structure && (
        <p className="text-muted mb-16">
          {structure.name}
          {structure.classes ? ` · ${structure.classes.length} ${t('stats.classes', { defaultValue: 'classe(s)' })}` : ''}
          {` · ${t('stats.since', { defaultValue: 'depuis le' })} ${from}`}
        </p>
      )}

      <div className="d-flex gap-16 flex-wrap align-items-start">
        {/* Comptes par profil */}
        <section className="card p-16 flex-grow-1" style={{ minWidth: 340 }}>
          <h2 style={{ fontSize: 18 }} className="mb-12">{t('stats.accounts', { defaultValue: 'Comptes & connexions par profil' })}</h2>
          {accountsQuery.isLoading && <p>{t('stats.loading', { defaultValue: 'Chargement…' })}</p>}
          {!accountsQuery.isLoading && profiles.length === 0 && (
            <p className="text-muted">{t('stats.accounts.empty', { defaultValue: 'Aucune donnée de connexion.' })}</p>
          )}
          {profiles.length > 0 && (
            <table className="table mb-0">
              <thead>
                <tr>
                  <th>{t('stats.profile', { defaultValue: 'Profil' })}</th>
                  <th className="text-end">{t('stats.auth', { defaultValue: 'Connexions' })}</th>
                  <th className="text-end">{t('stats.visitors', { defaultValue: 'Visiteurs' })}</th>
                  <th className="text-end">{t('stats.activated', { defaultValue: 'Activés' })}</th>
                </tr>
              </thead>
              <tbody>
                {profiles.map((p) => (
                  <tr key={p.profile}>
                    <td>{profileLabel(p.profile)}</td>
                    <td className="text-end">{p.authentications}</td>
                    <td className="text-end">{p.uniqueVisitors}</td>
                    <td className="text-end">{p.activated}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        {/* Accès par module */}
        <section className="card p-16 flex-grow-1" style={{ minWidth: 340 }}>
          <h2 style={{ fontSize: 18 }} className="mb-12">{t('stats.access', { defaultValue: 'Accès par application' })}</h2>
          {accessQuery.isLoading && <p>{t('stats.loading', { defaultValue: 'Chargement…' })}</p>}
          {!accessQuery.isLoading && modules.length === 0 && (
            <p className="text-muted">{t('stats.access.empty', { defaultValue: 'Aucune donnée d\'accès.' })}</p>
          )}
          {modules.length > 0 && (
            <ul className="list-unstyled mb-0">
              {modules.map((m) => (
                <li key={m.module} className="py-4 border-bottom">
                  <div className="d-flex justify-content-between">
                    <span>{m.module}</span>
                    <span className="text-muted">{m.total}</span>
                  </div>
                  {/* Barre proportionnelle (accessibilité : valeur textuelle ci-dessus) */}
                  <div aria-hidden="true" style={{ height: 6, background: '#e9ecef', borderRadius: 3, marginTop: 4 }}>
                    <div style={{ height: 6, width: `${maxAccess ? Math.round((m.total / maxAccess) * 100) : 0}%`, background: '#4bafd5', borderRadius: 3 }} />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}

export default Dashboard;
