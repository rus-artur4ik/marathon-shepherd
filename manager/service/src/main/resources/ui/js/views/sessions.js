// Sessions: who holds which devices, new sessions, and one session in detail.
import { del, get, post, query, segment } from '../api.js';
import {
  actionButton, button, card, chips, code, confirmAction, copyButton, details, errorState, field, h, input, loading, mount, mountIfChanged,
  pageHeader, select, showDialog, stateBadge, table, toast, toastError,
} from '../dom.js';
import { debounce, duration, parsePairs, sessionLabel, timeCell, TTL_CHOICES } from '../format.js';
import { onLiveEvent } from '../live.js';

const STATUSES = [['active', 'Active'], ['READY', 'Ready'], ['PENDING', 'Queued'], ['RELEASED', 'Released'], ['EXPIRED', 'Expired'], ['FAILED', 'Failed'], ['', 'All']];
const IDLE_CHOICES = [['', 'Never'], ['900', 'After 15 minutes'], ['3600', 'After 1 hour'], ['14400', 'After 4 hours']];
const ACTIVE = new Set(['READY', 'PENDING']);

export default {
  title: 'Sessions',

  async mount(main, ctx) {
    const view = {
      scope: ctx.params.get('scope') ?? (ctx.canHold ? 'mine' : 'all'),
      status: ctx.params.get('status') ?? 'active',
      id: ctx.params.get('id'),
    };
    const list = h('div');
    const detail = h('div');
    let heartbeat = null;

    const remember = () => {
      const hash = `#/sessions${query({ scope: view.scope, status: view.status === 'active' ? undefined : view.status || 'all', id: view.id })}`;
      window.history.replaceState(null, '', hash);
    };
    const canManage = (session) => ctx.isAdmin || session.owner === ctx.user.username;

    const extend = async (session) => {
      const updated = await showDialog({
        title: `Extend ${sessionLabel(session)}`,
        body: field('Keep it until', select('ttl', TTL_CHOICES, '3600'), 'Counted from now, within the lifetime your quota allows.'),
        submitLabel: 'Extend',
        onSubmit: (data) => post(`/api/v1/sessions/${segment(session.id)}/extend`, { ttlSeconds: Number(data.get('ttl')) }),
      });
      if (updated) {
        toast(`Session now lasts until ${new Date(updated.expiresAt).toLocaleString()}.`, 'ok');
        await refresh();
      }
    };

    const release = async (session) => {
      const confirmed = await confirmAction({
        title: 'Release session?',
        message: `The devices of ${sessionLabel(session)} go back to the pool. Tests still using them will lose them.`,
        confirmLabel: 'Release',
        danger: true,
      });
      if (!confirmed) return;
      await del(`/api/v1/sessions/${segment(session.id)}`);
      toast('Session released.', 'ok');
      await refresh();
    };

    const create = async () => {
      const created = await showDialog({
        title: 'New session',
        wide: true,
        body: h('div', { class: 'form-grid' },
          field('Devices', input('devices', { type: 'number', min: 1, max: 100, value: '1', required: true })),
          field('API levels', input('api', { placeholder: '34, 33..35, >=33' }), 'Any level when empty.'),
          field('Device type', select('deviceType', [['', 'Any'], ['physical', 'Physical'], ['emulator', 'Emulator']], '')),
          field('Labels', input('labels', { placeholder: 'form=phone' }), 'Every device must carry all of them.'),
          field('Keep for', select('ttl', TTL_CHOICES, '3600')),
          field('Release when idle', select('idle', IDLE_CHOICES, ''), 'Only while nobody checks in; this page does while it is open.'),
          field('Name', input('name', { placeholder: 'e.g. nightly build 42', maxlength: 200 }))),
        submitLabel: 'Create session',
        onSubmit: (data) => post('/api/v1/sessions', {
          maxDevices: Number(data.get('devices')),
          api: data.get('api').trim() || undefined,
          deviceType: data.get('deviceType') || undefined,
          labels: parsePairs(data.get('labels')),
          ttlSeconds: Number(data.get('ttl')),
          idleTimeoutSeconds: data.get('idle') ? Number(data.get('idle')) : undefined,
          name: data.get('name').trim() || undefined,
        }),
      });
      if (created) {
        toast(created.status === 'READY' ? 'Your devices are ready.' : `Queued at position ${created.queuePosition ?? '?'}; the page updates when devices free up.`, 'ok');
        view.scope = 'mine';
        view.status = 'active';
        view.id = created.id;
        await refresh();
      }
    };

    const renderList = async () => {
      const sessions = await get(`/api/v1/sessions${query({ owner: view.scope === 'mine' ? 'me' : undefined, status: view.status === 'active' ? undefined : view.status })}`);
      if (!ctx.isCurrent()) return;
      const rows = view.status === 'active' ? sessions.filter((session) => ACTIVE.has(session.status)) : sessions;
      const open = (session) => {
        view.id = session.id;
        remember();
        renderDetail().catch(toastError);
      };
      mountIfChanged(list, { rows, scope: view.scope, status: view.status, selected: view.id }, () => table([
        { label: 'Session', cell: (session) => h('a', { href: '#', onClick: (event) => { event.preventDefault(); open(session); } }, sessionLabel(session)) },
        view.scope === 'all' ? { label: 'Owner', cell: (session) => session.owner ?? '—' } : null,
        { label: 'Status', cell: (session) => [stateBadge(session.status), session.queuePosition ? h('div', { class: 'cell-sub' }, `#${session.queuePosition} in queue`) : null] },
        { label: 'Devices', cell: (session) => `${session.allocatedDevices}/${session.requestedDevices}` },
        { label: 'Wants', cell: (session) => [session.api ? `API ${session.api}` : 'any API', session.deviceType ? `, ${session.deviceType}` : ''].join('') },
        { label: 'Created', cell: (session) => timeCell(session.createdAt) },
        { label: 'Ends', cell: (session) => (ACTIVE.has(session.status) ? timeCell(session.expiresAt) : '—') },
        {
          label: '',
          class: 'actions',
          cell: (session) => (ACTIVE.has(session.status) && canManage(session)
            ? [actionButton('Extend', () => extend(session), { small: true }), actionButton('Release', () => release(session), { small: true })]
            : null),
        },
      ].filter(Boolean), rows, {
        empty: view.scope === 'mine' ? 'You have no sessions here. Create one, or take a device on the Devices page.' : 'No sessions match.',
        rowClass: (session) => (session.id === view.id ? 'row-selected' : null),
      }));
    };

    const renderDetail = async () => {
      clearInterval(heartbeat);
      heartbeat = null;
      if (!view.id) {
        mount(detail);
        delete detail.dataset.shows;
        return;
      }
      if (detail.dataset.shows !== view.id) {
        mount(detail, card('Session', loading()));
        detail.dataset.shows = view.id;
      }
      let session;
      try {
        session = await get(`/api/v1/sessions/${segment(view.id)}`);
      } catch (error) {
        mount(detail, card('Session', errorState(error)));
        delete detail.dataset.shows;
        return;
      }
      if (!ctx.isCurrent()) return;
      const close = button('Close', () => {
        view.id = null;
        remember();
        renderDetail();
        renderList().catch(toastError);
      }, { kind: 'ghost', small: true });
      const commands = (session.adbServers ?? []).map((server) => `adb -H ${server.host} -P ${server.port} devices`);
      mountIfChanged(detail, session, () => h('section', { class: 'card' },
        h('div', { class: 'card-header' }, h('h2', { class: 'card-title' }, session.name ?? session.id), close),
        h('div', { class: 'stack' },
          details([
            ['ID', code(session.id)],
            ['Status', [stateBadge(session.status), session.queuePosition ? ` #${session.queuePosition} in queue` : '']],
            ['Owner', session.owner],
            ['Devices', `${session.allocatedDevices} of ${session.requestedDevices}`],
            ['API levels', session.api ?? 'any'],
            ['Device type', session.deviceType ?? 'any'],
            ['Labels', Object.keys(session.labels ?? {}).length ? chips(session.labels) : null],
            ['Asked for', session.deviceIds?.length ? session.deviceIds.join(', ') : null],
            ['Created', timeCell(session.createdAt)],
            ['Ends', ACTIVE.has(session.status) ? timeCell(session.expiresAt) : null],
            ['Idle release', session.idleTimeoutSeconds ? `after ${duration(session.idleTimeoutSeconds)} without a check-in` : null],
            ['Last check-in', session.lastHeartbeatAt ? timeCell(session.lastHeartbeatAt) : null],
            ['Metadata', Object.keys(session.metadata ?? {}).length ? chips(session.metadata) : null],
          ]),
          session.status === 'READY' && commands.length ? h('div', {},
            h('p', { class: 'field-label' }, 'Connect with adb'),
            commands.map((command) => h('div', { class: 'command' }, code(command), copyButton(command)))) : null,
          session.devices?.length ? table([
            { label: 'Device', cell: (device) => [h('div', { class: 'cell-title' }, device.model ?? device.localId), h('div', { class: 'cell-sub mono' }, device.id)] },
            { label: 'API', cell: (device) => device.apiLevel ?? '—' },
            { label: 'Serial', cell: (device) => code(device.localId) },
          ], session.devices) : null,
          ACTIVE.has(session.status) && canManage(session) ? h('div', { class: 'page-actions' },
            actionButton('Extend', () => extend(session)),
            actionButton('Release', () => release(session), { kind: 'danger' })) : null)));
      // A session that is released when idle stays alive while someone watches it here.
      if (session.status === 'READY' && session.idleTimeoutSeconds && canManage(session)) {
        const every = Math.max(15, Math.min(60, Math.floor(session.idleTimeoutSeconds / 3))) * 1000;
        heartbeat = setInterval(() => post(`/api/v1/sessions/${segment(session.id)}/heartbeat`).catch(() => {}), every);
      }
    };

    const refresh = () => Promise.all([renderList(), renderDetail()]);

    const tabs = h('div', { class: 'tabs', role: 'tablist' }, [['mine', 'Mine'], ['all', 'Everyone']].map(([value, label]) => {
      const tab = h('button', { type: 'button', class: 'tab', role: 'tab', 'aria-selected': String(view.scope === value) }, label);
      tab.addEventListener('click', () => {
        view.scope = value;
        tabs.querySelectorAll('.tab').forEach((other) => other.setAttribute('aria-selected', String(other === tab)));
        remember();
        renderList().catch(toastError);
      });
      return tab;
    }));
    const statusSelect = select('status', STATUSES, view.status === 'all' ? '' : view.status);
    if (view.status === 'all') view.status = '';
    statusSelect.addEventListener('change', () => {
      view.status = statusSelect.value;
      remember();
      renderList().catch(toastError);
    });

    mount(main,
      pageHeader('Sessions', 'A session holds devices until it is released or ends. Queued sessions get devices in turn.',
        ctx.canHold ? button('New session', () => create().catch(toastError), { kind: 'primary' }) : null),
      h('div', { class: 'toolbar' }, tabs, field('Status', statusSelect)),
      h('div', { class: 'split' }, list, detail));
    await refresh();

    const later = debounce(() => refresh().catch(() => {}), 600);
    const stop = onLiveEvent((event) => {
      if (event.type.startsWith('session.')) later();
    });
    const timer = setInterval(later, 30000);
    return () => {
      stop();
      clearInterval(timer);
      clearInterval(heartbeat);
    };
  },
};
