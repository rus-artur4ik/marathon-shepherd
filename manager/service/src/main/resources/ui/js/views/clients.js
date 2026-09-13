// API clients: keys for CI jobs, scripts, adapters and agents (admin).
import { del, get, patch, post, query, segment } from '../api.js';
import {
  actionButton, button, checkbox, code, confirmAction, field, h, input, mount, pageHeader, select, showDialog, showSecret, stateBadge,
  table, toast, toastError,
} from '../dom.js';
import { quotaText, timeCell } from '../format.js';
import { quotaFields, readQuota } from './quota.js';

const ROLES = [['user', 'User: takes devices'], ['viewer', 'Viewer: reads only'], ['admin', 'Admin: everything'], ['provider', 'Provider: an adapter that registers']];
const blankToNull = (value) => (String(value ?? '').trim() === '' ? null : String(value).trim());

function usage(key) {
  return `export MSH_URL=${window.location.origin}\nexport MSH_TOKEN=${key}\nmshctl whoami`;
}

export default {
  title: 'API clients',

  async mount(main, ctx) {
    let includeRevoked = false;
    const results = h('div');

    const revealKey = (response, verb) => showSecret({
      title: `API key for ${response.client.name}`,
      message: `The key was ${verb}. Put it where the client reads it, such as a CI secret.`,
      secret: response.apiKey,
      usage: usage(response.apiKey),
    });

    const create = async () => {
      const created = await showDialog({
        title: 'Create API client',
        wide: true,
        body: [
          h('div', { class: 'form-grid' },
            field('Name', input('name', { required: true, placeholder: 'e.g. nightly-ci', autocomplete: 'off' })),
            field('Role', select('role', ROLES, 'user')),
            field('Description', input('description', { placeholder: 'What uses this key' }))),
          h('p', { class: 'field-hint' }, 'Limits; leave empty for the configured defaults.'),
          quotaFields(),
        ],
        submitLabel: 'Create client',
        onSubmit: (data) => post('/api/v1/admin/clients', {
          name: data.get('name').trim(),
          role: data.get('role'),
          description: blankToNull(data.get('description')),
          quota: readQuota(data),
        }),
      });
      if (created) {
        await render();
        await revealKey(created, 'created');
      }
    };

    const edit = async (client) => {
      const updated = await showDialog({
        title: `Edit ${client.name}`,
        wide: true,
        body: [
          h('div', { class: 'form-grid' },
            field('Role', select('role', ROLES, client.role)),
            field('Description', input('description', { value: client.description ?? '' }))),
          h('p', { class: 'field-hint' }, 'Limits; leave empty for the configured defaults.'),
          quotaFields(client.quota),
        ],
        onSubmit: (data) => patch(`/api/v1/admin/clients/${segment(client.id)}`, {
          role: data.get('role'),
          description: blankToNull(data.get('description')),
          quota: readQuota(data),
        }),
      });
      if (updated) {
        toast(`${client.name} updated.`, 'ok');
        await render();
      }
    };

    const rotate = async (client) => {
      const rotated = await confirmAction({
        title: `Rotate the key of ${client.name}?`,
        message: 'The current key stops working now. Whatever uses it needs the new one.',
        confirmLabel: 'Rotate key',
        danger: true,
        onSubmit: () => post(`/api/v1/admin/clients/${segment(client.id)}/rotate`, {}),
      });
      if (rotated?.apiKey) {
        await render();
        await revealKey(rotated, 'replaced');
      }
    };

    const revoke = async (client) => {
      const done = await confirmAction({
        title: `Revoke ${client.name}?`,
        message: 'The key stops working now and cannot be restored.',
        confirmLabel: 'Revoke',
        danger: true,
        extra: checkbox('releaseSessions', 'Also release its device sessions'),
        onSubmit: (data) => del(`/api/v1/admin/clients/${segment(client.id)}${query({ releaseSessions: data.get('releaseSessions') === 'on' })}`),
      });
      if (done) {
        toast(`${client.name} is revoked.`, 'ok');
        await render();
      }
    };

    const render = async () => {
      const clients = await get(`/api/v1/admin/clients${query({ includeRevoked })}`);
      if (!ctx.isCurrent()) return;
      mount(results, table([
        { label: 'Client', cell: (client) => [h('div', { class: 'cell-title' }, client.name), h('div', { class: 'cell-sub' }, client.description ?? client.id)] },
        { label: 'Role', cell: (client) => stateBadge(client.role) },
        { label: 'Key', cell: (client) => code(`${client.keyPrefix}…`) },
        { label: 'Limits', cell: (client) => h('span', { class: 'small' }, quotaText(client.effectiveQuota)) },
        { label: 'Last used', cell: (client) => timeCell(client.lastUsedAt) },
        { label: 'Created', cell: (client) => [timeCell(client.createdAt), client.createdBy ? h('div', { class: 'cell-sub' }, `by ${client.createdBy}`) : null] },
        { label: 'State', cell: (client) => stateBadge(client.active ? 'active' : 'revoked') },
        {
          label: '',
          class: 'actions',
          cell: (client) => (client.active ? [
            actionButton('Edit', () => edit(client), { small: true }),
            actionButton('Rotate key', () => rotate(client), { small: true }),
            actionButton('Revoke', () => revoke(client), { small: true }),
          ] : null),
        },
      ], clients, { empty: 'No API clients yet. Create one for each CI job, script or agent.' }));
    };

    const revokedToggle = checkbox('includeRevoked', 'Show revoked clients');
    revokedToggle.querySelector('input').addEventListener('change', (event) => {
      includeRevoked = event.target.checked;
      render().catch(toastError);
    });
    mount(main,
      pageHeader('API clients', 'Each CI job, script, adapter or agent gets its own key, role and limits. Keys are shown once.',
        button('Create client', () => create().catch(toastError), { kind: 'primary' })),
      h('div', { class: 'toolbar' }, revokedToggle),
      results);
    await render();
  },
};
