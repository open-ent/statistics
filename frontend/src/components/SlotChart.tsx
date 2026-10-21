import { formatInt } from '../utils';

/**
 * Histogramme SVG à créneaux nommés (heures du jour, jours de la semaine) : le créneau le plus chargé est
 * mis en évidence. Même approche que `BarChart` : libellé accessible, `<title>` par barre et tableau masqué.
 */
export function SlotChart({ labels, values, peak, label, color = '#4bafd5', peakColor = '#e67e22' }: {
  labels: string[];
  values: number[];
  peak: number | null;
  label: string;
  color?: string;
  peakColor?: string;
}) {
  const max = Math.max(1, ...values);
  const width = Math.max(320, values.length * 30);
  const height = 170;
  const top = 12;
  const bottom = 30;
  const plot = height - top - bottom;
  const step = width / values.length;
  return (
    <figure className="mb-0" data-chart={label}>
      <div style={{ overflowX: 'auto' }}>
        <svg role="img" aria-label={label} viewBox={`0 0 ${width} ${height}`} width={width} height={height} style={{ maxWidth: '100%' }}>
          <line x1="0" y1={top + plot} x2={width} y2={top + plot} stroke="#ced4da" />
          <text x="2" y={top + 8} fontSize="10" fill="#6c757d">{formatInt(max)}</text>
          {values.map((v, i) => {
            const h = Math.round((v / max) * plot);
            const x = i * step + step * 0.15;
            return (
              <g key={labels[i]}>
                <rect x={x} y={top + plot - h} width={step * 0.7} height={h} fill={i === peak ? peakColor : color} rx="2" data-bar data-peak={i === peak ? 'true' : undefined}>
                  <title>{`${labels[i]} : ${formatInt(v)}`}</title>
                </rect>
                <text x={x + (step * 0.7) / 2} y={height - 14} fontSize="9" textAnchor="middle" fill="#495057">{labels[i]}</text>
              </g>
            );
          })}
        </svg>
      </div>
      <figcaption className="visually-hidden">
        <table>
          <caption>{label}</caption>
          <tbody>
            {values.map((v, i) => (
              <tr key={labels[i]}><th scope="row">{labels[i]}</th><td>{formatInt(v)}</td></tr>
            ))}
          </tbody>
        </table>
      </figcaption>
    </figure>
  );
}

export default SlotChart;
