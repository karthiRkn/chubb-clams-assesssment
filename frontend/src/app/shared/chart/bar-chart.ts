import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import type { Chart, ChartConfiguration, Plugin } from 'chart.js';
import { BaseChartDirective } from 'ng2-charts';
import { CHART_FONT, CHART_INK, SERIES_COLORS } from './chart-theme';

export interface BarSeries {
  name: string;
  values: readonly number[];
  /** Index into the categorical palette (0-3). Pick it by entity so a series keeps its colour. Defaults to its position. */
  slot?: number;
}

const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });
const full = new Intl.NumberFormat('en');

/**
 * Bar chart following the data-viz method:
 *  - marks: at most 24px thick, 4px rounded data end and square at the baseline, hairline solid grid, 2px surface gap
 *    between stacked segments (never a drawn border around a mark);
 *  - one series: no legend box, one colour, and the value at the tip of each bar (selective labelling);
 *  - two or more series: a legend is always shown; no per-segment numbers (the tooltip and the table carry them);
 *  - the tooltip lists every series at that category, value first;
 *  - every chart has a table-view twin, so nothing is only reachable by hover or by colour.
 */
@Component({
  selector: 'app-bar-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BaseChartDirective, MatButtonModule, MatIconModule],
  template: `
    <figure class="figure">
      <figcaption class="head">
        <div class="titles">
          <h3 class="title">{{ title() }}</h3>
          @if (subtitle()) {
            <p class="subtitle">{{ subtitle() }}</p>
          }
        </div>
        <button mat-button type="button" class="toggle" (click)="showTable.set(!showTable())" [attr.aria-pressed]="showTable()">
          <mat-icon>{{ showTable() ? 'bar_chart' : 'table_chart' }}</mat-icon>
          {{ showTable() ? 'Chart view' : 'Table view' }}
        </button>
      </figcaption>

      @if (series().length > 1) {
        <ul class="legend" aria-label="Legend">
          @for (s of series(); track s.name; let i = $index) {
            <li><span class="swatch" [style.background]="color(s, i)"></span>{{ s.name }}</li>
          }
        </ul>
      }

      @if (isEmpty()) {
        <p class="empty muted">{{ emptyText() }}</p>
      } @else if (showTable()) {
        <div class="table-scroll">
          <table class="data">
            <caption class="cdk-visually-hidden">{{ title() }}</caption>
            <thead>
              <tr>
                <th scope="col">{{ categoryLabel() }}</th>
                @for (s of series(); track s.name) {
                  <th scope="col" class="num">{{ s.name }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (label of labels(); track label; let row = $index) {
                <tr>
                  <th scope="row">{{ label }}</th>
                  @for (s of series(); track s.name) {
                    <td class="num">{{ format(s.values[row] ?? 0) }}</td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <div class="canvas-wrap" [style.height.px]="height()">
          <canvas
            baseChart
            role="img"
            [attr.aria-label]="ariaLabel()"
            [type]="'bar'"
            [data]="config().data"
            [options]="config().options"
            [plugins]="[valueLabels]"
          ></canvas>
        </div>
      }
    </figure>
  `,
  styles: `
    .figure {
      margin: 0;
    }
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
    }
    .title {
      font: var(--mat-sys-title-medium);
    }
    .subtitle {
      font: var(--mat-sys-body-small);
      color: var(--app-muted);
    }
    .toggle {
      flex: none;
    }
    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 16px;
      margin: 8px 0 4px;
      padding: 0;
      list-style: none;
      font: var(--mat-sys-body-small);
      color: #52514e;
    }
    .legend li {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .swatch {
      width: 10px;
      height: 10px;
      border-radius: 2px;
    }
    .canvas-wrap {
      position: relative;
      width: 100%;
      margin-top: 8px;
    }
    .empty {
      padding: 32px 0;
      text-align: center;
    }
    .table-scroll {
      overflow-x: auto;
      margin-top: 8px;
    }
    .data {
      width: 100%;
      border-collapse: collapse;
      font: var(--mat-sys-body-medium);
    }
    .data th,
    .data td {
      padding: 8px 12px;
      border-bottom: 1px solid var(--app-border);
      text-align: left;
    }
    .data thead th {
      font: var(--mat-sys-label-large);
      color: var(--app-muted);
    }
    .data tbody th {
      font-weight: 400;
    }
    .num {
      text-align: right !important;
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class BarChart {
  readonly title = input.required<string>();
  readonly subtitle = input<string | null>(null);
  /** What the chart shows, for screen readers (the table view is the full text alternative). */
  readonly ariaLabel = input.required<string>();
  readonly labels = input.required<readonly string[]>();
  readonly series = input.required<readonly BarSeries[]>();
  /** `horizontal` for named categories (officers, statuses); `vertical` for few short labels. */
  readonly orientation = input<'horizontal' | 'vertical'>('horizontal');
  /** Segments of one bar add up to a total (open workload by status). Not used for amounts in different currencies. */
  readonly stacked = input(false);
  readonly categoryLabel = input('Category');
  readonly emptyText = input('Nothing to show yet.');

  protected readonly showTable = signal(false);

  protected readonly isEmpty = computed(
    () => this.labels().length === 0 || this.series().every((s) => s.values.every((v) => v === 0)),
  );

  /** Room for every row plus the axis band, so the card never needs a nested scroll. */
  protected readonly height = computed(() =>
    this.orientation() === 'horizontal' ? this.labels().length * 44 + 56 : 300,
  );

  /** Value at the tip of each bar: only for a single series, where it cannot become noise. */
  private readonly labelValues = computed(() => this.series().length === 1);

  /** Stable plugin object (reads the latest inputs when it draws), so Chart.js is not rebuilt on every change. */
  protected readonly valueLabels: Plugin<'bar'> = {
    id: 'valueLabels',
    afterDatasetsDraw: (chart: Chart) => this.drawValues(chart),
  };

  protected format(value: number): string {
    return full.format(value);
  }

  protected color(series: BarSeries, index: number): string {
    return SERIES_COLORS[(series.slot ?? index) % SERIES_COLORS.length];
  }

  protected readonly config = computed<ChartConfiguration<'bar'>>(() => {
    const horizontal = this.orientation() === 'horizontal';
    const stacked = this.stacked() && this.series().length > 1;
    const single = this.series().length === 1;

    return {
      type: 'bar',
      data: {
        labels: [...this.labels()],
        datasets: this.series().map((s, i) => ({
          label: s.name,
          data: [...s.values],
          backgroundColor: this.color(s, i),
          // The 2px surface-coloured edge IS the gap between touching segments; unstacked bars get none.
          borderColor: CHART_INK.surface,
          borderWidth: stacked ? 2 : 0,
          borderRadius: single || !stacked ? 4 : 0,
          // Data end rounded, baseline end square.
          borderSkipped: 'start' as const,
          maxBarThickness: 24,
          barPercentage: 0.8,
          categoryPercentage: stacked ? 0.8 : 0.9,
        })),
      },
      options: {
        indexAxis: horizontal ? 'y' : 'x',
        responsive: true,
        maintainAspectRatio: false,
        animation: { duration: 250 },
        layout: { padding: horizontal ? { right: single ? 44 : 8 } : { top: single ? 22 : 8 } },
        interaction: { mode: 'index', axis: horizontal ? 'y' : 'x', intersect: false },
        scales: {
          x: {
            stacked,
            border: { display: !horizontal, color: CHART_INK.baseline },
            grid: { display: horizontal, color: CHART_INK.gridline, lineWidth: 1, drawTicks: false },
            ticks: this.axisTicks(horizontal ? 'value' : 'category'),
            beginAtZero: true,
          },
          y: {
            stacked,
            border: { display: horizontal, color: CHART_INK.baseline },
            grid: { display: !horizontal, color: CHART_INK.gridline, lineWidth: 1, drawTicks: false },
            ticks: this.axisTicks(horizontal ? 'category' : 'value'),
            beginAtZero: true,
          },
        },
        plugins: {
          // A single series needs no legend box (the title says what it is); several get an HTML legend above.
          legend: { display: false },
          tooltip: {
            backgroundColor: CHART_INK.surface,
            borderColor: CHART_INK.border,
            borderWidth: 1,
            titleColor: CHART_INK.secondary,
            bodyColor: CHART_INK.primary,
            padding: 10,
            cornerRadius: 8,
            displayColors: true,
            boxWidth: 12,
            boxHeight: 3,
            usePointStyle: false,
            titleFont: { family: CHART_FONT, size: 12, weight: 400 },
            bodyFont: { family: CHART_FONT, size: 13, weight: 500 },
            callbacks: {
              // Value leads, series name follows.
              label: (item) => `${full.format(item.parsed[horizontal ? 'x' : 'y'] ?? 0)}  ${item.dataset.label ?? ''}`.trim(),
            },
          },
        },
      },
    };
  });

  private axisTicks(kind: 'value' | 'category') {
    return kind === 'value'
      ? {
          color: CHART_INK.muted,
          font: { family: CHART_FONT, size: 11 },
          precision: 0,
          maxTicksLimit: 6,
          padding: 6,
          callback: (value: string | number) => compact.format(Number(value)),
        }
      : {
          color: CHART_INK.secondary,
          font: { family: CHART_FONT, size: 12 },
          padding: 8,
        };
  }

  private drawValues(chart: Chart): void {
    if (!this.labelValues()) {
      return;
    }
    const horizontal = this.orientation() === 'horizontal';
    const ctx = chart.ctx;
    const dataset = chart.data.datasets[0];
    chart.getDatasetMeta(0).data.forEach((bar, index) => {
      const value = Number(dataset.data[index] ?? 0);
      const { x, y } = bar.getProps(['x', 'y'], true);
      ctx.save();
      ctx.fillStyle = CHART_INK.secondary;
      ctx.font = `500 12px ${CHART_FONT}`;
      if (horizontal) {
        ctx.textAlign = 'left';
        ctx.textBaseline = 'middle';
        ctx.fillText(full.format(value), x + 8, y);
      } else {
        ctx.textAlign = 'center';
        ctx.textBaseline = 'bottom';
        ctx.fillText(full.format(value), x, y - 6);
      }
      ctx.restore();
    });
  }
}
