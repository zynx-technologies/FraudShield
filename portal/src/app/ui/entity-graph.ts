import { afterNextRender, Component, effect, ElementRef, input, OnDestroy, viewChild } from '@angular/core';
import cytoscape from 'cytoscape';
import { CaseView } from '../api';

/** Links between accounts, devices, payees, and addresses, drawn with Cytoscape.js (FR-6, design section 10). */
@Component({
  selector: 'fs-entity-graph',
  template: `
    <div #host class="graph" role="img" [attr.aria-label]="summary()"></div>
    <ul class="sr-only">
      @for (e of links().edges; track $index) { <li>{{ e.from }} {{ e.relation.toLowerCase() }} {{ e.to }}</li> }
    </ul>
  `,
})
export class EntityGraph implements OnDestroy {
  links = input.required<CaseView['links']>();
  private host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private cy?: cytoscape.Core;

  summary() {
    const flagged = this.links().nodes.filter((n) => n.flagged).length;
    return `Linked entities: ${this.links().nodes.length} entities, ${this.links().edges.length} links, ${flagged} flagged`;
  }

  constructor() {
    afterNextRender(() => this.draw());
    effect(() => {
      this.links();
      if (this.cy) this.draw();
    });
  }

  private draw() {
    try {
      this.cy?.destroy();
      const l = this.links();
      this.cy = cytoscape({
        container: this.host().nativeElement,
        elements: [
          ...l.nodes.map((n) => ({ data: { id: n.id, label: n.label, type: n.type }, classes: [n.focus ? 'focus' : '', n.flagged ? 'flagged' : '', n.type.toLowerCase()].join(' ') })),
          ...l.edges.filter((e) => l.nodes.some((n) => n.id === e.from) && l.nodes.some((n) => n.id === e.to))
            .map((e, i) => ({ data: { id: `e${i}`, source: e.from, target: e.to } })),
        ],
        layout: { name: 'concentric', concentric: (node: cytoscape.NodeSingular) => (node.hasClass('focus') ? 2 : 1), levelWidth: () => 1, minNodeSpacing: 28, padding: 12 },
        userZoomingEnabled: false,
        userPanningEnabled: false,
        autoungrabify: true,
        style: [
          { selector: 'node', style: { 'background-color': '#eef0f5', 'border-width': 1, 'border-color': '#9aa3b5', label: 'data(label)', 'font-size': 8, 'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'wrap', 'text-max-width': '52px', width: 46, height: 46, color: '#1f2937' } },
          { selector: 'node.account', style: { 'background-color': '#fdf0d0', 'border-color': '#d9a441' } },
          { selector: 'node.focus', style: { 'background-color': '#fde4e4', 'border-color': '#b91c1c', 'border-width': 2, 'font-weight': 'bold', width: 56, height: 56 } },
          { selector: 'node.flagged', style: { 'border-color': '#b91c1c', 'border-width': 2 } },
          { selector: 'edge', style: { width: 1, 'line-color': '#9aa3b5', 'curve-style': 'straight' } },
        ],
      });
    } catch {
      // No canvas (unit tests). The list above still describes every link.
    }
  }

  ngOnDestroy() {
    this.cy?.destroy();
  }
}