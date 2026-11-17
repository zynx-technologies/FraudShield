import { DestroyRef, inject } from '@angular/core';

/**
 * Runs load() now (unless loadNow is false), again every intervalMs (if given), and whenever the local-development user changes.
 * Live updates are polled in this release; the design's SignalR push replaces polling later.
 */
export function autoReload(load: () => void, intervalMs?: number, loadNow = true) {
  const destroy = inject(DestroyRef);
  if (loadNow) load();
  const onUser = () => load();
  window.addEventListener('fs-user-changed', onUser);
  const timer = intervalMs ? setInterval(load, intervalMs) : undefined;
  destroy.onDestroy(() => {
    window.removeEventListener('fs-user-changed', onUser);
    if (timer) clearInterval(timer);
  });
}