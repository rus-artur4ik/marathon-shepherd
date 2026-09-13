// Audit log: who did what, newest first (admin).
import { get, query } from '../api.js';
import { button, chips, code, field, h, input, mount, pageHeader, select, stateBadge, table, toastError } from '../dom.js';
import { debounce, timeCell } from '../format.js';

const ACTIONS = [
  ['', 'Every action'], ['auth.', 'Sign-in and sign-out'], ['user.', 'Users'], ['client.', 'API clients'], ['session.', 'Sessions'],
  ['device.', 'Devices'], ['provider.', 'Providers'], ['config.', 'Configuration'], ['lease.', 'Leases'],
];
const PAGE_SIZE = 100;

export default {
  title: 'Audit log',

  async mount(main, ctx) {
    const filters = { action: '', actor: '', target: '' };
    const rows = [];
    const results = h('div');
    const more = button('Older entries', () => load(true).catch(toastError), { small: true });
    let nextBefore = null;

    const load = async (older = false) => {
      const page = await get(`/api/v1/audit${query({ ...filters, limit: PAGE_SIZE, before: older ? nextBefore : undefined })}`);
      if (!ctx.isCurrent()) return;
      if (!older) rows.length = 0;
      rows.push(...page.entries);
      nextBefore = page.nextBefore ?? null;
      more.hidden = nextBefore === null;
      mount(results, table([
        { label: 'When', class: 'nowrap', cell: (entry) => timeCell(entry.at) },
        { label: 'Who', cell: (entry) => [h('div', { class: 'cell-title' }, entry.actor), entry.actorId && entry.actorId !== entry.actor ? h('div', { class: 'cell-sub mono' }, entry.actorId) : null] },
        { label: 'Action', cell: (entry) => code(entry.action) },
        { label: 'Target', cell: (entry) => (entry.target ? h('span', { class: 'mono small' }, entry.target) : '—') },
        { label: 'Outcome', cell: (entry) => stateBadge(entry.outcome) },
        { label: 'Details', cell: (entry) => chips(entry.details) },
        { label: 'From', cell: (entry) => entry.origin ?? '—' },
      ], rows, { empty: 'No entries match.' }));
    };

    const actionSelect = select('action', ACTIONS, '');
    const actorInput = input('actor', { placeholder: 'client or username' });
    const targetInput = input('target', { placeholder: 'session, device, user…' });
    const apply = debounce(() => load().catch(toastError), 400);
    actionSelect.addEventListener('change', () => {
      filters.action = actionSelect.value;
      apply();
    });
    actorInput.addEventListener('input', () => {
      filters.actor = actorInput.value.trim();
      apply();
    });
    targetInput.addEventListener('input', () => {
      filters.target = targetInput.value.trim();
      apply();
    });

    mount(main,
      pageHeader('Audit log', 'Every sign-in, account change, session and admin action, newest first.'),
      h('div', { class: 'toolbar' }, field('Action', actionSelect), field('Who', actorInput), field('Target', targetInput)),
      h('div', { class: 'stack' }, results, h('div', {}, more)));
    await load();
  },
};
