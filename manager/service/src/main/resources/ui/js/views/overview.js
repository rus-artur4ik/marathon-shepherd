// Overview: the fleet at a glance, your own sessions and what just happened.
import { del, get, segment } from '../api.js';
import { actionButton, card, confirmAction, h, mountIfChanged, pageHeader, stateBadge, table, toast } from '../dom.js';
import { debounce, plural, quotaText, sessionLabel, timeCell } from '../format.js';
import { onLiveEvent, recentEvents } from '../live.js';
import { feedItem } from './activity.js';

const ACTIVE = new Set(['READY', 'PENDING']);

/** /health answers 503 with the same body when a provider is down, so read it either way. */
async function health() {
  try {
    const response = await fetch('/health', { cache: 'no-store' });
    return await response.json();
  } catch {
    return null;
  }
}

function stat(label, value, detail) {
  return h('section', { class: 'card stat' },
    h('span', { class: 'stat-label' }, label),
    h('span', { class: 'stat-value' }, value),
    h('span', { class: 'stat-detail' }, detail));
}

export default {
  title: 'Overview',

  async mount(main, ctx) {
    const feed = h('ul', { class: 'feed feed-compact' });
    const render = async () => {
      const [status, fleet, me, mine] = await Promise.all([
        health(),
        get('/api/v1/devices'),
        ctx.refreshMe(),
        get('/api/v1/sessions?owner=me'),
      ]);
      if (!ctx.isCurrent()) return;
      const devices = fleet.devices ?? [];
      const count = (state) => devices.filter((device) => device.state === state).length;
      const active = mine.filter((session) => ACTIVE.has(session.status));
      const sessionCounts = status?.sessions ?? { ready: 0, pending: 0, allocatedDevices: 0 };

      const release = async (session) => {
        const confirmed = await confirmAction({
          title: 'Release session?',
          message: `The devices of ${sessionLabel(session)} go back to the pool.`,
          confirmLabel: 'Release',
          danger: true,
        });
        if (!confirmed) return;
        await del(`/api/v1/sessions/${segment(session.id)}`);
        toast('Session released.', 'ok');
        await render();
      };

      const sessionsTable = table([
        { label: 'Session', cell: (session) => h('a', { href: `#/sessions?id=${encodeURIComponent(session.id)}` }, sessionLabel(session)) },
        { label: 'Status', cell: (session) => [stateBadge(session.status), session.queuePosition ? h('span', { class: 'cell-sub' }, ` #${session.queuePosition} in queue`) : null] },
        { label: 'Devices', cell: (session) => `${session.allocatedDevices}/${session.requestedDevices}` },
        { label: 'Expires', cell: (session) => timeCell(session.expiresAt) },
        { label: '', class: 'actions', cell: (session) => actionButton('Release', () => release(session), { small: true }) },
      ], active, { empty: ctx.canHold ? 'You hold no devices. Take some from the Devices page.' : 'Your role can look but not take devices.' });

      const providers = status?.providers ?? [];
      const providerRows = table([
        { label: 'Provider', cell: (provider) => h('a', { href: '#/providers' }, provider.name) },
        { label: 'Health', cell: (provider) => stateBadge(provider.status, provider.error) },
        { label: 'Available', cell: (provider) => (provider.total === undefined ? '—' : `${provider.available}/${provider.total}`) },
      ], providers, { empty: 'No providers are configured.' });

      const events = recentEvents().slice(0, 8);
      const snapshot = { status: status && { ...status, checkedAt: undefined }, fleet, me, mine, events: events.map((event) => event.id) };
      mountIfChanged(main, snapshot, () => {
        feed.replaceChildren(...events.map(feedItem));
        return [
        pageHeader('Overview', `Signed in as ${ctx.user.displayName ?? ctx.user.username}.`,
          ctx.canHold ? h('a', { class: 'button button-primary', href: '#/devices' }, 'Take devices') : null),
        h('div', { class: 'stack' },
          h('div', { class: 'grid' },
            stat('Available devices', fleet.totalAvailable, `${fleet.totalBusy} busy · ${count('offline')} offline · ${count('maintenance')} in maintenance`),
            stat('Ready sessions', sessionCounts.ready, `${sessionCounts.pending} queued · ${plural(sessionCounts.allocatedDevices, 'device')} in use`),
            stat('Healthy providers', status ? `${status.providersHealthy}/${status.providersTotal}` : '—', status?.status === 'healthy' ? 'All providers answer' : 'Some providers need attention'),
            stat('Your sessions', me.usage.activeSessions, `${plural(me.usage.devices, 'device')} · quota: ${quotaText(me.quota)}`)),
          h('div', { class: 'columns' },
            card('Your active sessions', sessionsTable),
            card('Providers', providerRows)),
          card('Recent activity', feed.children.length ? feed : h('p', { class: 'muted' }, 'Nothing has happened since you opened the UI.'), h('a', { href: '#/activity' }, 'All live activity')))];
      });
    };

    await render();
    const refresh = debounce(() => render().catch(() => {}), 700);
    const stop = onLiveEvent(refresh);
    const timer = setInterval(refresh, 30000);
    return () => {
      stop();
      clearInterval(timer);
    };
  },
};
