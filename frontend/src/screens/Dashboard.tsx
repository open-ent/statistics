import { useEdificeClient } from '@open-ent/react';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';

import { api, exportUrl, EntityLevel, ExportMode, Frequency, HttpError } from '../api';
import { LineChart } from '../components/LineChart';
import { SlotChart } from '../components/SlotChart';
import { dateLocale, dayLabels } from '../locale';
import { multiSeries, ratioSeries, MultiSeries, accessByModule, accountsByProfile, dailyPeak, weeklyPeak, formatInt, devicesTotals, kpis, profileLabel, rollingYearStart, splitAccess } from '../utils';

type TabId = 'home' | 'connections' | 'usage' | 'devices' | 'peaks' | 'accounts';

/** Onglets thématiques sous l'accueil résumé. */
const TABS: Array<{ id: TabId; key: string; label: string }> = [
  { id: 'home', key: 'stats.tab.home', label: 'Accueil' },
  { id: 'connections', key: 'stats.tab.connections', label: 'Connexions' },
  { id: 'usage', key: 'stats.tab.usage', label: 'Applications et connecteurs' },
  { id: 'devices', key: 'stats.tab.devices', label: 'Supports' },
  { id: 'peaks', key: 'stats.tab.peaks', label: 'Fréquentation' },
  { id: 'accounts', key: 'stats.tab.accounts', label: 'Comptes' },
];

/** Tableau de bord Statistiques : établissement + comptes par profil + accès par module. */
export function Dashboard() {
  const { t } = useTranslation(['stats', 'common']);
  const { user, init } = useEdificeClient();
  const from = rollingYearStart(new Date());
  const [tab, setTab] = useState<TabId>('home');
  const [exportMode, setExportMode] = useState<ExportMode>('structure');
  const [frequency, setFrequency] = useState<Frequency>('month');
  // Choix explicite de l'utilisateur, sinon sa structure de rattachement : nécessaire pour
  // les profils Collectivité/ADML, dont /stats/structures peut renvoyer plusieurs
  // établissements (périmètre de fonction) en plus de leur structure d'affectation.
  const [selectedStructureId, setSelectedStructureId] = useState<string | null>(null);
  // Lien direct depuis dashboard/structure-info (?structureId=<id> dans le hash de route) :
  // préselectionne l'établissement visé sans passer par le sélecteur.
  const [searchParams] = useSearchParams();
  const requestedStructureId = searchParams.get('structureId');

  const structuresQuery = useQuery({ queryKey: ['stats', 'structures'], queryFn: () => api.getStructures() });
  const structuresList = structuresQuery.data ?? [];
  const structureId = selectedStructureId ?? requestedStructureId ?? user?.structures?.[0] ?? structuresList[0]?.id ?? '';
  // Périmètre : tout l'établissement, ou une de ses classes (comme l'AngularJS, niveau « classe »).
  const [selectedClassId, setSelectedClassId] = useState('');
  const structureForClasses = structuresList.find((s) => s.id === structureId) ?? structuresList[0];
  const classes = structureForClasses?.classes ?? [];
  const classId = classes.some((c) => c.id === selectedClassId) ? selectedClassId : '';
  const entityId = classId || structureId;
  const level: EntityLevel = classId ? 'class' : 'structure';
  const accountsQuery = useQuery({ queryKey: ['stats', 'accounts', level, entityId, from, frequency], queryFn: () => api.getAccounts(entityId, from, frequency, level), enabled: !!structureId });
  const accessQuery = useQuery({ queryKey: ['stats', 'access', level, entityId, from, frequency], queryFn: () => api.getAccess(entityId, from, frequency, level), enabled: !!structureId });

  // Connexions par appareil : requête annexe, son échec masque seulement ce bloc.
  const devicesQuery = useQuery({ queryKey: ['stats', 'devices', level, entityId, from, frequency], queryFn: () => api.getAccountsByDevice(entityId, from, frequency, level), enabled: !!structureId, retry: false });
  // Pics de connexion : requête annexe à l'heure près, son échec masque seulement ces deux blocs.
  const hourlyQuery = useQuery({ queryKey: ['stats', 'hourly', level, entityId, from], queryFn: () => api.getAccountsHourly(entityId, from, level), enabled: !!structureId, retry: false });
  const hourly = useMemo(() => dailyPeak(hourlyQuery.data ?? []), [hourlyQuery.data]);
  const weekly = useMemo(() => weeklyPeak(hourlyQuery.data ?? []), [hourlyQuery.data]);
  const hourLabels = useMemo(() => Array.from({ length: 24 }, (_, h) => `${String(h).padStart(2, '0')}h`), []);
  const devices = useMemo(() => devicesTotals(devicesQuery.data ?? []), [devicesQuery.data]);
  const maxDevice = devices[0]?.total ?? 0;

  const structure = structuresList.find((s) => s.id === structureId) ?? structuresList[0];
  const profiles = useMemo(() => accountsByProfile(accountsQuery.data ?? []), [accountsQuery.data]);
  const split = useMemo(() => splitAccess(accessQuery.data ?? []), [accessQuery.data]);
  const modules = useMemo(() => accessByModule(split.apps), [split]);
  const connectors = useMemo(() => accessByModule(split.connectors), [split]);
  const maxConnector = connectors[0]?.total ?? 0;
  const maxAccess = modules[0]?.total ?? 0;
  const connectionsByProfile = useMemo(() => multiSeries(accountsQuery.data ?? [], (r) => r.authentications ?? 0, (r) => r.profile), [accountsQuery.data]);
  const visitorsByProfile = useMemo(() => multiSeries(accountsQuery.data ?? [], (r) => r.unique_visitors ?? 0, (r) => r.profile), [accountsQuery.data]);
  const ratioByProfile = useMemo(() => ratioSeries(accountsQuery.data ?? []), [accountsQuery.data]);
  const devicesByPeriod = useMemo(() => multiSeries(devicesQuery.data ?? [], (r) => r.authentications ?? 0, (r) => r.device_type || 'other', false), [devicesQuery.data]);
  // comptes activés et chargés : deux courbes (totaux tous profils), comme l'indicateur AngularJS
  const activation = useMemo<MultiSeries>(() => {
    const act = multiSeries(accountsQuery.data ?? [], (r) => r.activated ?? 0, () => 'activated', false);
    const load = multiSeries(accountsQuery.data ?? [], (r) => r.loaded ?? 0, () => 'loaded', false);
    return { periods: act.periods, series: [...act.series, ...load.series] };
  }, [accountsQuery.data]);
  const seriesLabel = (key: string) => {
    const known: Record<string, string> = {
      Total: t('stats.total', { defaultValue: 'Total' }),
      Teacher: t('stats.teacher', { defaultValue: 'Enseignants' }),
      Personnel: t('stats.personnel', { defaultValue: 'Personnels' }),
      Relative: t('stats.relative', { defaultValue: 'Parents' }),
      Student: t('stats.student', { defaultValue: 'Élèves' }),
      Guest: t('stats.others', { defaultValue: 'Autres' }),
      activated: t('stats.activated', { defaultValue: 'Comptes activés' }),
      loaded: t('stats.loaded', { defaultValue: 'Comptes chargés' }),
    };
    return known[key] ?? t(`stats.device.${key}`, { defaultValue: key });
  };
  const k = useMemo(() => kpis(accountsQuery.data ?? [], split.apps), [accountsQuery.data, split]);
  const sinceLabel = `${t('stats.since', { defaultValue: 'depuis' })} ${new Date(`${from}T00:00:00`).toLocaleDateString(dateLocale(), { month: 'long', year: 'numeric' })}`;
  const none = '—';
  const lastActivated = (() => { const a = activation.series.find((x) => x.key === 'activated'); return a ? a.values[a.values.length - 1] ?? 0 : 0; })();
  // cartes de l'accueil : mêmes indicateurs que l'AngularJS, chacune ouvre l'onglet qui la détaille
  const cards: Array<{ id: string; label: string; value: string; since: string; tab: TabId }> = [
    { id: 'connexions', label: t('stats.connections', { defaultValue: 'Connexions' }), value: formatInt(k.totalAuth), since: sinceLabel, tab: 'connections' },
    { id: 'visiteurs', label: t('stats.uniqueVisitors', { defaultValue: 'Utilisateurs distincts' }), value: formatInt(k.visitors), since: sinceLabel, tab: 'connections' },
    { id: 'ratio', label: t('stats.connectionsByUniqueVisitors', { defaultValue: 'Connexions / utilisateur distinct' }), value: k.visitors > 0 ? formatInt(Math.round((k.totalAuth / k.visitors) * 10) / 10) : '0', since: sinceLabel, tab: 'connections' },
    { id: 'appli', label: t('stats.mostUsedApp', { defaultValue: 'Appli la plus utilisée' }), value: modules[0] ? t(`stats.module.${modules[0].module}`, { defaultValue: modules[0].module }) : none, since: sinceLabel, tab: 'usage' },
    { id: 'connecteur', label: t('stats.mostUsedConnector', { defaultValue: 'Connecteur le plus utilisé' }), value: connectors[0] ? t(`stats.module.${connectors[0].module}`, { defaultValue: connectors[0].module }) : none, since: sinceLabel, tab: 'usage' },
    { id: 'support', label: t('stats.devices', { defaultValue: 'Support le plus utilisé' }), value: devices[0] ? t(`stats.device.${devices[0].device}`, { defaultValue: devices[0].device }) : none, since: sinceLabel, tab: 'devices' },
    { id: 'pic-jour', label: t('stats.dailyPeak', { defaultValue: 'Pic de connexions journalier' }), value: hourly.peak !== null ? hourLabels[hourly.peak] : none, since: sinceLabel, tab: 'peaks' },
    { id: 'pic-semaine', label: t('stats.weeklyPeak', { defaultValue: 'Pic de connexions hebdomadaire' }), value: weekly.peak !== null ? dayLabels()[weekly.peak] : none, since: sinceLabel, tab: 'peaks' },
    { id: 'comptes', label: t('stats.activatedAccounts', { defaultValue: 'Comptes activés' }), value: formatInt(lastActivated), since: sinceLabel, tab: 'accounts' },
  ];

  // Un refus (401/403) n'est pas une panne : message dédié ; sinon message générique. Avant, les deux
  // s'affichaient comme « aucune donnée ».
  const failure = [structuresQuery, accountsQuery, accessQuery].find((q) => q.isError);
  const forbidden = failure?.error instanceof HttpError && (failure.error.status === 401 || failure.error.status === 403);
  // Sans droit sur l'établissement entier (ex. enseignant) mais avec des classes : on bascule d'office
  // sur la première classe au lieu de n'afficher qu'un refus, comme l'AngularJS.
  const firstClassId = [...classes].sort((a, b) => a.name.localeCompare(b.name))[0]?.id ?? '';
  useEffect(() => {
    if (forbidden && !classId && firstClassId) setSelectedClassId(firstClassId);
  }, [forbidden, classId, firstClassId]);
  const errorBox = failure ? (
    <div className="alert alert-warning d-flex justify-content-between align-items-center" role="alert" data-stats-error>
      <span>
        {forbidden
          ? t('stats.error.forbidden', { defaultValue: "Vous n'avez pas accès aux statistiques de cet établissement." })
          : t('stats.error.load', { defaultValue: 'Impossible de charger les statistiques pour le moment.' })}
      </span>
      {!forbidden && (
        <button type="button" className="btn btn-sm btn-secondary" onClick={() => [structuresQuery, accountsQuery, accessQuery].filter((q) => q.isError).forEach((q) => q.refetch())}>
          {t('stats.error.retry', { defaultValue: 'Réessayer' })}
        </button>
      )}
    </div>
  ) : null;

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
      {errorBox}
      <div className="d-flex justify-content-between align-items-end flex-wrap gap-8 mb-16">
        {structure && (
          <p className="text-muted mb-0">
            {structure.name}
            {structure.classes ? ` · ${structure.classes.length} ${t('stats.classes', { defaultValue: 'classe(s)' })}` : ''}
            {` · ${t('stats.since', { defaultValue: 'depuis le' })} ${new Date(`${from}T00:00:00`).toLocaleDateString(dateLocale())}`}
          </p>
        )}
        <div className="d-flex gap-8 flex-wrap">
          {structuresList.length > 1 && (
            <div>
              <label htmlFor="stats-structure" className="form-label">{t('stats.structure', { defaultValue: 'Établissement' })}</label>
              <select
                id="stats-structure"
                className="form-select"
                value={structureId}
                onChange={(e) => { setSelectedStructureId(e.target.value); setSelectedClassId(''); }}
              >
                {structuresList.map((s) => (
                  <option key={s.id} value={s.id}>{s.name}</option>
                ))}
              </select>
            </div>
          )}
          {classes.length > 0 && (
            <div>
              <label htmlFor="stats-class" className="form-label">{t('stats.class', { defaultValue: 'Périmètre' })}</label>
              <select id="stats-class" className="form-select" value={classId} onChange={(e) => setSelectedClassId(e.target.value)}>
                <option value="">{t('stats.class.all', { defaultValue: 'Tout l\'établissement' })}</option>
                {[...classes].sort((a, b) => a.name.localeCompare(b.name)).map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </select>
            </div>
          )}
          <div>
            <label htmlFor="stats-freq" className="form-label">{t('stats.frequency', { defaultValue: 'Granularité' })}</label>
            <select id="stats-freq" className="form-select" value={frequency} onChange={(e) => setFrequency(e.target.value as Frequency)}>
              <option value="day">{t('stats.freq.day', { defaultValue: 'Jour' })}</option>
              <option value="week">{t('stats.freq.week', { defaultValue: 'Semaine' })}</option>
              <option value="month">{t('stats.freq.month', { defaultValue: 'Mois' })}</option>
            </select>
          </div>
          <div>
            <label htmlFor="stats-export-mode" className="form-label">{t('stats.export.mode', { defaultValue: 'Export CSV' })}</label>
            <div className="d-flex gap-8">
              <select id="stats-export-mode" className="form-select" value={exportMode} onChange={(e) => setExportMode(e.target.value as ExportMode)}>
                <option value="structure">{t('stats.export.structure', { defaultValue: "Cet établissement" })}</option>
                <option value="classes">{t('stats.export.classes', { defaultValue: 'Classes de l\'établissement' })}</option>
                <option value="substructures">{t('stats.export.substructures', { defaultValue: 'Sous-structures' })}</option>
                <option value="aggregated">{t('stats.export.aggregated', { defaultValue: 'Données agrégées' })}</option>
              </select>
              {(['accounts', 'access'] as const).map((ind) => (
                <a
                  key={ind}
                  className={`btn btn-secondary text-nowrap${structureId ? '' : ' disabled'}`}
                  href={structureId ? exportUrl(ind, exportMode, structureId, from, frequency) : undefined}
                  data-export={ind}
                  target="_blank"
                  rel="noreferrer"
                >
                  {ind === 'accounts' ? t('stats.export.accounts', { defaultValue: 'Connexions' }) : t('stats.export.access', { defaultValue: 'Accès' })}
                </a>
              ))}
            </div>
          </div>
        </div>
      </div>

      <ul className="nav nav-tabs mb-16" role="tablist" data-stats-tabs>
        {TABS.map((x) => (
          <li key={x.id} className="nav-item" role="presentation">
            <button type="button" role="tab" aria-selected={tab === x.id} className={`nav-link${tab === x.id ? ' active' : ''}`} onClick={() => setTab(x.id)} data-stats-tab={x.id}>
              {t(x.key, { defaultValue: x.label })}
            </button>
          </li>
        ))}
      </ul>
      {tab === 'home' && (
        <div data-stats-panel="home">
          <div className="d-flex gap-16 flex-wrap mb-16" data-stats-cards>
            {cards.map((c) => (
              <button
                key={c.id}
                type="button"
                className="card p-16 text-start flex-grow-1"
                style={{ minWidth: 260, flexBasis: '30%', cursor: 'pointer' }}
                onClick={() => setTab(c.tab)}
                data-stats-card={c.id}
              >
                <div style={{ fontSize: 26, fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: '#2e8b3e' }} data-stats-card-value>{c.value}</div>
                <div style={{ fontWeight: 600 }}>{c.label}</div>
                <div className="text-muted" style={{ fontSize: 12 }}>{c.since}</div>
              </button>
            ))}
          </div>
          <section className="card p-16 mb-16" data-stats-definitions>
            <h2 style={{ fontSize: 16 }} className="mb-8">{t('stats.definitions', { defaultValue: '*Définitions' })}</h2>
            <p className="mb-4"><strong>{t('stats.uniqueVisitors', { defaultValue: 'Utilisateurs distincts' })} :</strong> {t('stats.def.uniqueVisitor', { defaultValue: "Nombre de personnes différentes qui se sont connectées au moins une fois au cours de la période : une personne connectée dix fois n'est comptée qu'une seule fois." })}</p>
            <p className="mb-0"><strong>{t('stats.connectionsByUniqueVisitors', { defaultValue: 'Connexions / utilisateur distinct' })} :</strong> {t('stats.def.connectionsByUniqueVisitor', { defaultValue: "Nombre moyen de connexions par personne au cours de la période : plus il est élevé, plus la plateforme est utilisée régulièrement." })}</p>
          </section>
        </div>
      )}
      {tab === 'connections' && (
        <div data-stats-panel="connections">
      {/* Évolution dans le temps : la granularité choisie ci-dessus s'y applique (elle n'avait aucun effet visuel). */}
      {connectionsByProfile.periods.length > 0 && (
        <div className="d-flex gap-16 flex-wrap mb-16">
          {[
            { id: 'connexions', title: t('stats.chart.connections', { defaultValue: 'Évolution du nombre de connexions' }), data: connectionsByProfile },
            { id: 'visiteurs', title: t('stats.chart.visitors', { defaultValue: "Évolution du nombre d'utilisateurs distincts" }), data: visitorsByProfile },
            { id: 'connexions-par-visiteur', title: t('stats.chart.ratio', { defaultValue: 'Évolution du nombre de connexions par utilisateur distinct' }), data: ratioByProfile },
          ].map((c) => (
            <section key={c.id} className="card p-16 flex-grow-1" style={{ minWidth: 420, flexBasis: '45%' }} data-stats-series={c.id}>
              <h2 style={{ fontSize: 18 }} className="mb-12">{c.title}</h2>
              <LineChart data={c.data} label={c.title} labelOf={seriesLabel} colorOf={c.id === 'comptes-actives' ? (key) => (key === 'activated' ? '#6fbe2e' : '#46afe6') : undefined} />
            </section>
          ))}
        </div>
      )}


        </div>
      )}
      {tab === 'usage' && (
        <div data-stats-panel="usage">
        <div className="d-flex gap-16 flex-wrap align-items-start">
        {/* Accès par module */}
        <section className="card p-16 flex-grow-1" style={{ minWidth: 340 }}>
          <h2 style={{ fontSize: 18 }} className="mb-12">{t('stats.access', { defaultValue: 'Accès par application' })}</h2>
          {accessQuery.isLoading && <p>{t('stats.loading', { defaultValue: 'Chargement…' })}</p>}
          {!accessQuery.isLoading && !accessQuery.isError && modules.length === 0 && (
            <p className="text-muted">{t('stats.access.empty', { defaultValue: 'Aucune donnée d\'accès.' })}</p>
          )}
          {modules.length > 0 && (
            <ul className="list-unstyled mb-0">
              {modules.map((m) => (
                <li key={m.module} className="py-4 border-bottom">
                  <div className="d-flex justify-content-between">
                    <span>{t(`stats.module.${m.module}`, { defaultValue: m.module })}</span>
                    <span className="text-muted">{formatInt(m.total)}</span>
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

        {/* Accès par connecteur (ENT → services externes : GAR, Moodle…) */}
        {connectors.length > 0 && (
          <section className="card p-16 flex-grow-1" style={{ minWidth: 340 }} data-stats-section="connecteurs">
            <h2 style={{ fontSize: 18 }} className="mb-12">{t('stats.connectors', { defaultValue: 'Accès par connecteur' })}</h2>
            <ul className="list-unstyled mb-0">
              {connectors.map((c) => (
                <li key={c.module} className="py-4 border-bottom">
                  <div className="d-flex justify-content-between">
                    <span>{c.module}</span>
                    <span className="text-muted">{formatInt(c.total)}</span>
                  </div>
                  <div aria-hidden="true" style={{ height: 6, background: '#e9ecef', borderRadius: 3, marginTop: 4 }}>
                    <div style={{ height: 6, width: `${maxConnector ? Math.round((c.total / maxConnector) * 100) : 0}%`, background: '#8e44ad', borderRadius: 3 }} />
                  </div>
                </li>
              ))}
            </ul>
          </section>
        )}

        </div>
        </div>
      )}
      {tab === 'devices' && (
        <div data-stats-panel="devices">
        <div className="d-flex gap-16 flex-wrap align-items-start">
        {/* Connexions par appareil */}
        {devices.length > 0 && (
          <section className="card p-16 flex-grow-1" style={{ minWidth: 340 }} data-stats-section="appareils">
            <h2 style={{ fontSize: 18 }} className="mb-12">{t('stats.devices.logins', { defaultValue: 'Connexions par appareil' })}</h2>
            <div className="mb-12" data-stats-series="appareils-evolution">
              <LineChart data={devicesByPeriod} label={t('stats.devices.logins', { defaultValue: 'Connexions par appareil' })} labelOf={seriesLabel} colorOf={(_k, i) => ['#e67e22', '#46afe6', '#6fbe2e', '#a348c0', '#ff3a55'][i % 5]} />
            </div>
            <ul className="list-unstyled mb-0">
              {devices.map((d) => (
                <li key={d.device} className="py-4 border-bottom">
                  <div className="d-flex justify-content-between">
                    <span>{t(`stats.device.${d.device}`, { defaultValue: d.device })}</span>
                    <span className="text-muted">{formatInt(d.total)}</span>
                  </div>
                  <div aria-hidden="true" style={{ height: 6, background: '#e9ecef', borderRadius: 3, marginTop: 4 }}>
                    <div style={{ height: 6, width: `${maxDevice ? Math.round((d.total / maxDevice) * 100) : 0}%`, background: '#e67e22', borderRadius: 3 }} />
                  </div>
                </li>
              ))}
            </ul>
          </section>
        )}
        </div>
        </div>
      )}
      {tab === 'peaks' && (
        <div data-stats-panel="peaks">
      {hourly.peak !== null && (
        <div className="d-flex gap-16 flex-wrap mb-16">
          <section className="card p-16 flex-grow-1" style={{ minWidth: 340 }} data-stats-section="pic-horaire">
            <h2 style={{ fontSize: 18 }} className="mb-4">{t('stats.chart.dailyPeak', { defaultValue: 'Pic de connexions dans la journée' })}</h2>
            <p className="text-muted mb-12" data-peak-value>{t('stats.chart.peak.at', { defaultValue: 'Pic : [[slot]]', slot: hourLabels[hourly.peak] })}</p>
            <SlotChart labels={hourLabels} values={hourly.values} peak={hourly.peak} label={t('stats.chart.dailyPeak', { defaultValue: 'Pic de connexions dans la journée' })} />
          </section>
          <section className="card p-16 flex-grow-1" style={{ minWidth: 340 }} data-stats-section="pic-hebdomadaire">
            <h2 style={{ fontSize: 18 }} className="mb-4">{t('stats.chart.weeklyPeak', { defaultValue: 'Pic de connexions dans la semaine' })}</h2>
            <p className="text-muted mb-12" data-peak-value>{t('stats.chart.peak.at', { defaultValue: 'Pic : [[slot]]', slot: weekly.peak !== null ? dayLabels()[weekly.peak] : '' })}</p>
            <SlotChart labels={dayLabels()} values={weekly.values} peak={weekly.peak} label={t('stats.chart.weeklyPeak', { defaultValue: 'Pic de connexions dans la semaine' })} color="#8e44ad" />
          </section>
        </div>
      )}


        </div>
      )}
      {tab === 'accounts' && (
        <div data-stats-panel="accounts">
      {activation.periods.length > 0 && (
        <section className="card p-16 mb-16" data-stats-series="comptes-actives">
          <h2 style={{ fontSize: 18 }} className="mb-12">{t('stats.chart.activated', { defaultValue: 'Évolution des comptes activés et chargés' })}</h2>
          <LineChart data={activation} label={t('stats.chart.activated', { defaultValue: 'Évolution des comptes activés et chargés' })} labelOf={seriesLabel} colorOf={(key) => (key === 'activated' ? '#6fbe2e' : '#46afe6')} />
        </section>
      )}
        <div className="d-flex gap-16 flex-wrap align-items-start">
        {/* Comptes par profil */}
        <section className="card p-16 flex-grow-1" style={{ minWidth: 340 }}>
          <h2 style={{ fontSize: 18 }} className="mb-12">{t('stats.accounts', { defaultValue: 'Comptes & connexions par profil' })}</h2>
          {accountsQuery.isLoading && <p>{t('stats.loading', { defaultValue: 'Chargement…' })}</p>}
          {!accountsQuery.isLoading && !accountsQuery.isError && profiles.length === 0 && (
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
                    <td>{t(`stats.profile.${p.profile}`, { defaultValue: profileLabel(p.profile) })}</td>
                    <td className="text-end">{formatInt(p.authentications)}</td>
                    <td className="text-end">{formatInt(p.uniqueVisitors)}</td>
                    <td className="text-end">{formatInt(p.activated)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        </div>
        </div>
      )}
    </div>
  );
}

export default Dashboard;
