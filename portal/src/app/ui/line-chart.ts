import { afterNextRender, Component, effect, ElementRef, input, OnDestroy, viewChild } from '@angular/core';
import * as echarts from 'echarts/core';
import { LineChart as EchartsLine } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([EchartsLine, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

export interface Series {
  name: string;
  values: (number | null)[];
  colour: string;
  dashed?: boolean;
}

/** Apache ECharts line chart (design section 10), styled like the mock-ups. */
@Component({
  selector: 'fs-line-chart',
  template: `<div #host class="chart" role="img" [attr.aria-label]="description()"></div>`,
})
export class LineChart implements OnDestroy {
  labels = input.required<string[]>();
  series = input.required<Series[]>();
  description = input<string>('Chart');
  yMin = input<number | null>(null);
  yMax = input<number | null>(null);
  ySuffix = input<string>('');

  private host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private chart?: echarts.ECharts;
  private resize?: ResizeObserver;

  constructor() {
    afterNextRender(() => {
      try {
        this.chart = echarts.init(this.host().nativeElement);
        this.resize = new ResizeObserver(() => this.chart?.resize());
        this.resize.observe(this.host().nativeElement);
        this.render();
      } catch {
        // No canvas (for example in unit tests). The data is still available to screen readers via the label.
      }
    });
    effect(() => {
      this.labels();
      this.series();
      this.render();
    });
  }

  private render() {
    if (!this.chart) return;
    const suffix = this.ySuffix();
    this.chart.setOption({
      animation: false,
      grid: { left: 36, right: 12, top: 12, bottom: 48 },
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, left: 0, icon: 'rect', itemWidth: 10, itemHeight: 10, textStyle: { color: '#6b7280', fontSize: 12 } },
      xAxis: { type: 'category', data: this.labels(), axisLine: { lineStyle: { color: '#d1d5db' } }, axisLabel: { color: '#6b7280', fontSize: 11 }, axisTick: { show: false } },
      yAxis: {
        type: 'value', min: this.yMin() ?? undefined, max: this.yMax() ?? undefined,
        splitLine: { lineStyle: { color: '#eef0f4' } }, axisLabel: { color: '#6b7280', fontSize: 11, formatter: `{value}${suffix}` },
      },
      series: this.series().map((s) => ({
        name: s.name, type: 'line', data: s.values, showSymbol: false, smooth: false, connectNulls: false,
        lineStyle: { color: s.colour, width: 2, type: s.dashed ? 'dashed' : 'solid' }, itemStyle: { color: s.colour },
      })),
    }, true);
  }

  ngOnDestroy() {
    this.resize?.disconnect();
    this.chart?.dispose();
  }
}