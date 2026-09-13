// Providers: every adapter the manager uses, its health, pool and what it offers.
import { del, get, segment } from '../api.js';
import { actionButton, badge, confirmAction, details, emptyState, h, mountIfChanged, pageHeader, stateBadge, toast } from '../dom.js';
import { debounce, timeCell } from '../format.js';
import { onLiveEvent } from '../live.js';

async function health() {
  try {
    return await (await fetch('/health', { cache: 'no-store' })).json();
  } catch {
    return null;
  }
}

function pool(status) {
  if (!status?.pool) return null;
  const { available, busy, total } = status.pool;
  return h('div', { class: 'pool' },
    h('meter', { min: 0, max: Math.max(total, 1), value: busy, title: `${busy} of ${total} busy` }),
    h('div', { class: 'pool-legend' }, h('span', {}, `${available} available · ${busy} busy`), h('span', {}, `${total} total`)));
}

function inventory(status) {
  const profiles = status?.inventory ?? [];
  if (!profiles.length) return null;
  return h('span', { class: 'chips' }, profiles.map((profile) => h('span', { class: 'chip' },
    `${profile.count} × ${[profile.manufacturer, profile.model].filter(Boolean).join(' ') || profile.deviceType}${profile.apiLevel ? ` (API ${profile.apiLevel})` : ''}`)));
}

export default {
  title: 'Providers',

  async mount(main, ctx) {
    const render = async () => {
      const [infos, fleet, summary] = await Promise.all([get('/api/v1/providers'), get('/api/v1/devices'), health()]);
      if (!ctx.isCurrent()) return;
      const statuses = new Map((fleet.providers ?? []).map((status) => [status.name, status]));
      const problems = new Map((summary?.providers ?? []).map((provider) => [provider.name, provider]));

      const remove = async (info) => {
        const confirmed = await confirmAction({
          title: `Remove ${info.name}?`,
          message: 'The registration is deleted and the provider gets no new sessions. If the adapter keeps running, it registers again at its next heartbeat.',
          confirmLabel: 'Remove',
          danger: true,
        });
        if (!confirmed) return;
        await del(`/api/v1/providers/${segment(info.name)}`);
        toast(`${info.name} removed.`, 'ok');
        await render();
      };

      const cards = infos.map((info) => {
        const status = statuses.get(info.name);
        const problem = problems.get(info.name);
        const healthText = info.healthy === undefined || info.healthy === null ? 'unknown' : info.healthy ? 'healthy' : 'unhealthy';
        const capabilities = status?.capabilities ?? {};
        return h('section', { class: 'card provider-card' },
          h('div', { class: 'card-header' },
            h('h2', { class: 'card-title' }, info.name),
            h('span', { class: 'chips' },
              stateBadge(healthText, problem?.error),
              badge(info.source, 'neutral'),
              info.active ? null : badge('inactive', 'muted', 'Its heartbeat lapsed; it gets no new sessions'))),
          pool(status),
          problem?.error ? h('p', { class: 'form-error' }, problem.error) : null,
          details([
            ['URL', info.url],
            ['ADB access host', info.accessHost ?? status?.adbHost],
            ['Adapter type', info.adapterType],
            ['Device types', (capabilities.supportedDeviceTypes ?? []).join(', ')],
            ['API levels', (capabilities.supportedApiLevels ?? []).join(', ')],
            ['Devices', inventory(status)],
            ['Registered by', info.registeredBy],
            ['Registered', info.registeredAt ? timeCell(info.registeredAt) : null],
            ['Last heartbeat', info.lastSeenAt ? timeCell(info.lastSeenAt) : null],
          ]),
          ctx.isAdmin && info.source === 'registered'
            ? h('div', {}, actionButton('Remove registration', () => remove(info), { kind: 'danger', small: true }))
            : null);
      });

      mountIfChanged(main, { infos, providers: fleet.providers, problems: summary?.providers }, () => [
        pageHeader('Providers', 'Adapters in msh.yaml and adapters that registered themselves. Health comes from the background poll.'),
        cards.length ? h('div', { class: 'columns' }, cards) : emptyState('No providers yet. Add one to msh.yaml or let an adapter register with a provider key.')]);
    };

    await render();
    const refresh = debounce(() => render().catch(() => {}), 700);
    const stop = onLiveEvent((event) => {
      if (event.type.startsWith('provider.') || event.type.startsWith('session.')) refresh();
    });
    const timer = setInterval(refresh, 15000);
    return () => {
      stop();
      clearInterval(timer);
    };
  },
};
