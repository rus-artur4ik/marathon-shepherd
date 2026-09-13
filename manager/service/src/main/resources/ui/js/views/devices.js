// Devices: every device of every provider, taking one, and maintenance.
import { del, get, post, put, query, segment } from '../api.js';
import { actionButton, chips, field, h, input, mount, mountIfChanged, pageHeader, select, showDialog, stateBadge, table, toast, toastError } from '../dom.js';
import { debounce, parsePairs, relativeTime, TTL_CHOICES } from '../format.js';
import { onLiveEvent } from '../live.js';

const STATES = [['', 'Any state'], ['available', 'Available'], ['busy', 'Busy'], ['offline', 'Offline'], ['maintenance', 'Maintenance']];
const TYPES = [['', 'Any type'], ['physical', 'Physical'], ['emulator', 'Emulator']];

export default {
  title: 'Devices',

  async mount(main, ctx) {
    const filters = { state: ctx.params.get('state') ?? '', provider: ctx.params.get('provider') ?? '', deviceType: '', api: '', labels: '' };
    const results = h('div', { class: 'stack' });
    const providerSelect = select('provider', [['', 'Any provider']], filters.provider);
    let providersKnown = false;

    const take = async (device) => {
      const created = await showDialog({
        title: `Take ${device.id}`,
        body: [
          field('Session name', input('name', { placeholder: 'What you are doing, e.g. checking the login flow', maxlength: 200 })),
          field('Keep it for', select('ttl', TTL_CHOICES, '3600'), 'You can extend or release it on the Sessions page.'),
        ],
        submitLabel: 'Take device',
        onSubmit: (data) => post('/api/v1/sessions', {
          deviceIds: [device.id],
          maxDevices: 1,
          ttlSeconds: Number(data.get('ttl')),
          name: data.get('name') || undefined,
        }),
      });
      if (created) {
        toast(`Session ${created.id} is ${created.status.toLowerCase()}.`, 'ok');
        ctx.navigate(`#/sessions?id=${encodeURIComponent(created.id)}`);
      }
    };

    const startMaintenance = async (device) => {
      const done = await showDialog({
        title: `Maintenance for ${device.id}`,
        body: [
          h('p', {}, 'New sessions will not get this device until you return it to service.'),
          field('Reason', input('reason', { placeholder: 'e.g. cracked screen, replacing the cable', maxlength: 500 })),
        ],
        submitLabel: 'Start maintenance',
        onSubmit: (data) => put(`/api/v1/devices/${segment(device.id)}/maintenance`, { reason: data.get('reason') || undefined }),
      });
      if (done) {
        toast(`${device.id} is in maintenance.`, 'ok');
        await render();
      }
    };

    const endMaintenance = async (device) => {
      await del(`/api/v1/devices/${segment(device.id)}/maintenance`);
      toast(`${device.id} is back in service.`, 'ok');
      await render();
    };

    const render = async (refresh = false) => {
      let labels;
      try {
        labels = Object.entries(parsePairs(filters.labels)).map(([key, value]) => `${key}=${value}`);
      } catch (error) {
        toastError(error);
        return;
      }
      const fleet = await get(`/api/v1/devices${query({ ...filters, labels: undefined, label: labels, refresh })}`);
      if (!ctx.isCurrent()) return;
      if (!providersKnown) {
        providersKnown = true;
        (fleet.providers ?? []).forEach((provider) => providerSelect.append(h('option', { value: provider.name }, provider.name)));
        providerSelect.value = filters.provider;
      }
      const pools = (fleet.providers ?? []).map((provider) => `${provider.name}: ${provider.pool.available}/${provider.pool.total} available`);
      mountIfChanged(results, fleet, () => [
        h('p', { class: 'muted small' }, `${fleet.totalAvailable} available and ${fleet.totalBusy} busy in all pools. ${pools.join(' · ')}`),
        table([
          {
            label: 'Device',
            cell: (device) => [
              h('div', { class: 'cell-title' }, [device.manufacturer, device.model].filter(Boolean).join(' ') || device.localId),
              h('div', { class: 'cell-sub mono' }, device.id),
            ],
          },
          {
            label: 'State',
            cell: (device) => [
              stateBadge(device.state, device.maintenance?.reason),
              device.maintenance ? h('div', { class: 'cell-sub' }, `${device.maintenance.by}, ${relativeTime(device.maintenance.since)}${device.maintenance.reason ? `: ${device.maintenance.reason}` : ''}`) : null,
            ],
          },
          { label: 'Type', cell: (device) => device.deviceType },
          { label: 'API', cell: (device) => device.apiLevel ?? '—' },
          { label: 'Labels', cell: (device) => chips(device.labels) },
          {
            label: 'Held by',
            cell: (device) => (device.sessionId
              ? [h('div', {}, device.owner ?? '—'), h('a', { class: 'cell-sub mono', href: `#/sessions?scope=all&status=&id=${encodeURIComponent(device.sessionId)}` }, device.sessionId)]
              : '—'),
          },
          {
            label: '',
            class: 'actions',
            cell: (device) => [
              ctx.canHold && device.state === 'available' ? actionButton('Take', () => take(device), { kind: 'primary', small: true }) : null,
              ctx.isAdmin && device.state !== 'maintenance' ? actionButton('Maintenance', () => startMaintenance(device), { small: true }) : null,
              ctx.isAdmin && device.state === 'maintenance' ? actionButton('Return to service', () => endMaintenance(device), { small: true }) : null,
            ],
          },
        ], fleet.devices ?? [], { empty: 'No device matches. Providers that report only pool sizes do not list their devices.' })]);
    };

    const stateSelect = select('state', STATES, filters.state);
    const typeSelect = select('deviceType', TYPES, filters.deviceType);
    const apiInput = input('api', { placeholder: '34, 33..35, >=33' });
    const labelInput = input('labels', { placeholder: 'form=phone, rooted=false' });
    const apply = () => render().catch(toastError);
    const typed = debounce(apply, 450);
    [[stateSelect, 'state'], [providerSelect, 'provider'], [typeSelect, 'deviceType']].forEach(([control, key]) => control.addEventListener('change', () => {
      filters[key] = control.value;
      apply();
    }));
    [[apiInput, 'api'], [labelInput, 'labels']].forEach(([control, key]) => control.addEventListener('input', () => {
      filters[key] = control.value.trim();
      typed();
    }));

    mount(main,
      pageHeader('Devices', 'Every device the providers report. Take one to get a session with it, or take several on the Sessions page.',
        actionButton('Refresh now', () => render(true), { title: 'Ask every provider instead of using the last poll' })),
      h('div', { class: 'toolbar' },
        field('State', stateSelect), field('Provider', providerSelect), field('Type', typeSelect), field('API level', apiInput), field('Labels', labelInput)),
      results);
    await render();

    const refresh = debounce(() => render().catch(() => {}), 800);
    const stop = onLiveEvent(refresh);
    const timer = setInterval(refresh, 20000);
    return () => {
      stop();
      clearInterval(timer);
    };
  },
};
