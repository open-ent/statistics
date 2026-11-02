import { dateLocale } from '../locale';
import { formatInt, MultiSeries, periodLabel, PROFILE_COLORS } from '../utils';

const FALLBACK = ['#4bafd5', '#8e44ad', '#e67e22', '#16a085', '#c0392b'];

/**
 * Courbes SVG multi-séries (une par profil ou par appareil) avec légende, quadrillage et valeurs au survol,
 * comme les graphiques AngularJS (Chart.js). Sans dépendance. Accessible : libellé du graphique, `<title>` sur
 * chaque point et tableau de valeurs masqué visuellement.
 */
export function LineChart({
  data,
  label,
  labelOf,
  colorOf,
}: {
  data: MultiSeries;
  label: string;
  labelOf: (key: string) => string;
  colorOf?: (key: string, index: number) => string;
}) {
  const { periods, series } = data;
  if (periods.length === 0 || series.length === 0) return null;
  const locale = dateLocale();
  const color = (key: string, i: number) => colorOf?.(key, i) ?? PROFILE_COLORS[key] ?? FALLBACK[i % FALLBACK.length];
  const max = Math.max(1, ...series.flatMap((s) => s.values));
  const width = Math.max(420, periods.length * 44);
  const height = 240;
  const left = 44;
  const top = 12;
  const bottom = 30;
  const plotW = width - left - 12;
  const plotH = height - top - bottom;
  const x = (i: number) => left + (periods.length === 1 ? plotW / 2 : (i * plotW) / (periods.length - 1));
  const y = (v: number) => top + plotH - (v / max) * plotH;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(max * f));
  // l'axe n'affiche qu'une étiquette sur n quand les périodes sont nombreuses
  const every = Math.ceil(periods.length / 12);
  return (
    <figure className="mb-0" data-chart={label}>
      <div style={{ overflowX: 'auto' }}>
        <svg role="img" aria-label={label} viewBox={`0 0 ${width} ${height}`} width={width} height={height} style={{ maxWidth: '100%' }}>
          {ticks.map((tk) => (
            <g key={tk}>
              <line x1={left} x2={width - 12} y1={y(tk)} y2={y(tk)} stroke="#e9ecef" />
              <text x={left - 6} y={y(tk) + 3} fontSize="10" textAnchor="end" fill="#6c757d">{formatInt(tk)}</text>
            </g>
          ))}
          {periods.map((p, i) =>
            i % every === 0 ? (
              <text key={p} x={x(i)} y={height - 10} fontSize="10" textAnchor="middle" fill="#495057">{periodLabel(p, locale)}</text>
            ) : null,
          )}
          {series.map((s, si) => {
            const c = color(s.key, si);
            return (
              <g key={s.key} data-series={s.key}>
                <polyline fill="none" stroke={c} strokeWidth={s.key === 'Total' ? 2.5 : 2} points={s.values.map((v, i) => `${x(i)},${y(v)}`).join(' ')} />
                {s.values.map((v, i) => (
                  <circle key={periods[i]} cx={x(i)} cy={y(v)} r="3.5" fill={c} data-point>
                    <title>{`${labelOf(s.key)} — ${periodLabel(periods[i], locale)} : ${formatInt(v)}`}</title>
                  </circle>
                ))}
              </g>
            );
          })}
        </svg>
      </div>
      <ul className="list-inline mb-0 mt-8" data-legend>
        {series.map((s, si) => (
          <li key={s.key} className="list-inline-item me-16">
            <span aria-hidden="true" style={{ display: 'inline-block', width: 14, height: 10, background: color(s.key, si), marginRight: 6, borderRadius: 2 }} />
            {labelOf(s.key)}
          </li>
        ))}
      </ul>
      <figcaption className="visually-hidden">
        <table>
          <caption>{label}</caption>
          <thead>
            <tr>
              <th />
              {series.map((s) => <th key={s.key}>{labelOf(s.key)}</th>)}
            </tr>
          </thead>
          <tbody>
            {periods.map((p, i) => (
              <tr key={p}>
                <th scope="row">{periodLabel(p, locale)}</th>
                {series.map((s) => <td key={s.key}>{formatInt(s.values[i])}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </figcaption>
    </figure>
  );
}

export default LineChart;
