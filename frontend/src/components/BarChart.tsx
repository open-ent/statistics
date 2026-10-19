import { periodLabel, SeriesPoint, seriesMax } from '../utils';
import { dateLocale } from '../locale';
import { formatInt } from '../utils';

/**
 * Histogramme SVG d'une série temporelle (une barre par période). Sans dépendance : le module n'avait aucun graphique.
 * Accessible : le graphique porte un libellé et chaque barre un `<title>` (période : valeur) ; le tableau de
 * valeurs est disponible sous le graphique pour les lecteurs d'écran.
 */
export function BarChart({ points, label, color = '#4bafd5' }: { points: SeriesPoint[]; label: string; color?: string }) {
  if (points.length === 0) return null;
  const max = seriesMax(points);
  const width = Math.max(320, points.length * 38);
  const height = 180;
  const top = 12;
  const bottom = 40;
  const plot = height - top - bottom;
  const step = width / points.length;
  const locale = dateLocale();
  return (
    <figure className="mb-0" data-chart={label}>
      <div style={{ overflowX: 'auto' }}>
        <svg role="img" aria-label={label} viewBox={`0 0 ${width} ${height}`} width={width} height={height} style={{ maxWidth: '100%' }}>
          <line x1="0" y1={top + plot} x2={width} y2={top + plot} stroke="#ced4da" />
          <text x="2" y={top + 8} fontSize="10" fill="#6c757d">{formatInt(max)}</text>
          {points.map((p, i) => {
            const h = Math.round((p.value / max) * plot);
            const x = i * step + step * 0.15;
            return (
              <g key={p.date}>
                <rect x={x} y={top + plot - h} width={step * 0.7} height={h} fill={color} rx="2" data-bar>
                  <title>{`${periodLabel(p.date, locale)} : ${formatInt(p.value)}`}</title>
                </rect>
                <text x={x + (step * 0.7) / 2} y={height - 24} fontSize="10" textAnchor="middle" fill="#495057">
                  {periodLabel(p.date, locale)}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      <figcaption className="visually-hidden">
        <table>
          <caption>{label}</caption>
          <tbody>
            {points.map((p) => (
              <tr key={p.date}>
                <th scope="row">{periodLabel(p.date, locale)}</th>
                <td>{formatInt(p.value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </figcaption>
    </figure>
  );
}

export default BarChart;
