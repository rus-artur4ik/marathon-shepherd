// Users: people who sign in, their roles, passwords and personal tokens (admin).
import { del, get, patch, post, query, segment } from '../api.js';
import {
  actionButton, button, checkbox, confirmAction, field, h, input, mount, pageHeader, select, showDialog, showSecret,
  stateBadge, table, toast, toastError,
} from '../dom.js';
import { quotaText, timeCell } from '../format.js';
import { quotaFields, readQuota } from './quota.js';

const ROLES = [['user', 'User: takes devices'], ['viewer', 'Viewer: looks only'], ['admin', 'Admin: everything']];
const blankToNull = (value) => (String(value ?? '').trim() === '' ? null : String(value).trim());

function signsInWith(user) {
  return user.provider && user.source === 'oidc' ? `OIDC (${user.provider})` : user.source === 'ldap' ? 'LDAP' : 'Password';
}

export default {
  title: 'Users',

  async mount(main, ctx) {
    let includeDisabled = false;
    const results = h('div');

    const create = async () => {
      const created = await showDialog({
        title: 'Create user',
        wide: true,
        body: [
          h('div', { class: 'form-grid' },
            field('Username', input('username', { required: true, autocomplete: 'off', autocapitalize: 'none', spellcheck: 'false' })),
            field('Role', select('role', ROLES, 'user')),
            field('Display name', input('displayName')),
            field('Email', input('email', { type: 'email' }))),
          h('p', { class: 'field-hint' }, 'Limits; leave empty for the configured defaults.'),
          quotaFields(),
          h('p', { class: 'field-hint' }, 'The user gets a one-time password and chooses their own at first sign-in. People from LDAP or OIDC appear here on their own when they first sign in.'),
        ],
        submitLabel: 'Create user',
        onSubmit: (data) => post('/api/v1/admin/users', {
          username: data.get('username').trim(),
          role: data.get('role'),
          displayName: blankToNull(data.get('displayName')),
          email: blankToNull(data.get('email')),
          quota: readQuota(data),
        }),
      });
      if (!created) return;
      await render();
      if (created.temporaryPassword) {
        await showSecret({
          title: `One-time password for ${created.user.username}`,
          message: 'Give it to them over a channel you trust. They must replace it when they first sign in.',
          secret: created.temporaryPassword,
        });
      }
    };

    const edit = async (user) => {
      const roleSelect = select('role', ROLES, user.role, { disabled: user.roleManagedByProvider });
      // A directory owns the names of the accounts it provisions.
      const local = user.source === 'local';
      const updated = await showDialog({
        title: `Edit ${user.username}`,
        wide: true,
        body: [
          h('div', { class: 'form-grid' },
            local ? field('Username', input('username', { value: user.username, autocapitalize: 'none', spellcheck: 'false' }),
              'The name they sign in with.') : null,
            field('Display name', input('displayName', { value: user.displayName ?? '' })),
            field('Email', input('email', { type: 'email', value: user.email ?? '' })),
            field('Role', roleSelect, user.roleManagedByProvider ? `Set by ${user.provider} groups at every sign-in.` : null)),
          h('p', { class: 'field-hint' }, 'Limits; leave empty for the configured defaults.'),
          quotaFields(user.quota),
        ],
        onSubmit: (data) => patch(`/api/v1/admin/users/${segment(user.id)}`, {
          username: local ? blankToNull(data.get('username')) : undefined,
          displayName: blankToNull(data.get('displayName')),
          email: blankToNull(data.get('email')),
          role: user.roleManagedByProvider ? undefined : data.get('role'),
          quota: readQuota(data),
        }),
      });
      if (updated) {
        toast(updated.username === user.username ? `${user.username} updated.` : `${user.username} is now ${updated.username}.`, 'ok');
        await render();
      }
    };

    const resetPassword = async (user) => {
      const reset = await confirmAction({
        title: `Reset ${user.username}'s password?`,
        message: 'They are signed out everywhere and get a one-time password to replace at their next sign-in.',
        confirmLabel: 'Reset password',
        danger: true,
        onSubmit: () => post(`/api/v1/admin/users/${segment(user.id)}/password`, {}),
      });
      if (reset?.temporaryPassword) {
        await showSecret({ title: `One-time password for ${user.username}`, message: 'Give it to them over a channel you trust.', secret: reset.temporaryPassword });
        await render();
      }
    };

    const disable = async (user) => {
      const done = await confirmAction({
        title: `Disable ${user.username}?`,
        message: 'They are signed out, their personal tokens stop working, and they cannot sign in until you enable them again.',
        confirmLabel: 'Disable',
        danger: true,
        extra: checkbox('releaseSessions', 'Also release their device sessions'),
        onSubmit: (data) => del(`/api/v1/admin/users/${segment(user.id)}${query({ releaseSessions: data.get('releaseSessions') === 'on' })}`),
      });
      if (done) {
        toast(`${user.username} is disabled.`, 'ok');
        await render();
      }
    };

    const enable = async (user) => {
      await patch(`/api/v1/admin/users/${segment(user.id)}`, { active: true });
      toast(`${user.username} is enabled.`, 'ok');
      await render();
    };

    const tokens = async (user) => {
      const body = h('div');
      const load = async () => {
        const list = await get(`/api/v1/admin/users/${segment(user.id)}/tokens`);
        mount(body, table([
          { label: 'Name', cell: (token) => [h('div', { class: 'cell-title' }, token.name), h('div', { class: 'cell-sub mono' }, `${token.prefix}…`)] },
          { label: 'Last used', cell: (token) => timeCell(token.lastUsedAt) },
          { label: 'Expires', cell: (token) => (token.revokedAt ? stateBadge('revoked') : token.expiresAt ? timeCell(token.expiresAt) : 'never') },
          {
            label: '',
            class: 'actions',
            cell: (token) => (token.revokedAt ? null : actionButton('Revoke', async () => {
              await del(`/api/v1/admin/users/${segment(user.id)}/tokens/${segment(token.id)}`);
              toast(`Token ${token.name} revoked.`, 'ok');
              await load();
            }, { small: true })),
          },
        ], list, { empty: `${user.username} has no personal tokens.` }));
      };
      await load();
      await showDialog({ title: `Personal tokens of ${user.username}`, wide: true, body, submitLabel: 'Close', cancelLabel: null });
    };

    const render = async () => {
      const users = await get(`/api/v1/admin/users${query({ includeDisabled })}`);
      if (!ctx.isCurrent()) return;
      mount(results, table([
        {
          label: 'User',
          cell: (user) => [h('div', { class: 'cell-title' }, user.username), h('div', { class: 'cell-sub' }, [user.displayName, user.email].filter(Boolean).join(' · ') || user.id)],
        },
        { label: 'Role', cell: (user) => [stateBadge(user.role), user.roleManagedByProvider ? h('div', { class: 'cell-sub' }, 'from groups') : null] },
        { label: 'Signs in with', cell: signsInWith },
        { label: 'State', cell: (user) => stateBadge(!user.active ? 'disabled' : user.mustChangePassword ? 'must change password' : 'active') },
        { label: 'Limits', cell: (user) => h('span', { class: 'small' }, quotaText(user.effectiveQuota)) },
        { label: 'Last sign-in', cell: (user) => timeCell(user.lastLoginAt) },
        {
          label: '',
          class: 'actions',
          cell: (user) => (user.active ? [
            actionButton('Edit', () => edit(user), { small: true }),
            user.source === 'local' ? actionButton('Reset password', () => resetPassword(user), { small: true }) : null,
            actionButton('Tokens', () => tokens(user), { small: true }),
            user.id === ctx.user.id ? null : actionButton('Disable', () => disable(user), { small: true }),
          ] : actionButton('Enable', () => enable(user), { small: true })),
        },
      ], users, { empty: 'No users yet.' }));
    };

    const disabledToggle = checkbox('includeDisabled', 'Show disabled users');
    disabledToggle.querySelector('input').addEventListener('change', (event) => {
      includeDisabled = event.target.checked;
      render().catch(toastError);
    });
    mount(main,
      pageHeader('Users', 'People who sign in with a password, LDAP or an OIDC provider. CI jobs and agents use API clients instead.',
        button('Create user', () => create().catch(toastError), { kind: 'primary' })),
      h('div', { class: 'toolbar' }, disabledToggle),
      results);
    await render();
  },
};
