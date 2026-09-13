// Your account: who you are, your password and your personal API tokens.
import { del, get, post, segment } from '../api.js';
import { actionButton, button, card, details, field, h, input, mount, pageHeader, select, showDialog, showSecret, stateBadge, table, toast, toastError } from '../dom.js';
import { plural, quotaText, timeCell } from '../format.js';

const EXPIRY = [['30', '30 days'], ['90', '90 days'], ['365', '1 year'], ['', 'Never']];

function passwordCard(user) {
  if (user.source !== 'local') {
    const where = user.source === 'ldap' ? 'your directory (LDAP)' : `${user.provider ?? 'your identity provider'}`;
    return card('Password', h('p', { class: 'muted' }, `Your password is managed by ${where}. Change it there.`));
  }
  const alert = h('p', { class: 'form-error', role: 'alert', hidden: true });
  const submit = h('button', { type: 'submit', class: 'button button-primary' }, 'Change password');
  const form = h('form', { class: 'auth-form' },
    field('Current password', input('current', { type: 'password', autocomplete: 'current-password', required: true })),
    field('New password', input('next', { type: 'password', autocomplete: 'new-password', required: true })),
    field('New password again', input('repeat', { type: 'password', autocomplete: 'new-password', required: true })),
    alert,
    h('div', {}, submit));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const data = new FormData(form);
    alert.hidden = true;
    if (data.get('next') !== data.get('repeat')) {
      alert.textContent = 'The new passwords do not match.';
      alert.hidden = false;
      return;
    }
    submit.disabled = true;
    try {
      await post('/api/v1/me/password', { currentPassword: data.get('current'), newPassword: data.get('next') }, { quietUnauthorized: true });
      form.reset();
      toast('Password changed. Your other browser sessions are signed out.', 'ok');
    } catch (error) {
      alert.textContent = error.message;
      alert.hidden = false;
    } finally {
      submit.disabled = false;
    }
  });
  return card('Password', form);
}

export default {
  title: 'Your account',

  async mount(main, ctx) {
    const tokensBody = h('div');

    const loadTokens = async () => {
      const tokens = await get('/api/v1/me/tokens');
      if (!ctx.isCurrent()) return;
      mount(tokensBody, table([
        { label: 'Name', cell: (token) => [h('div', { class: 'cell-title' }, token.name), h('div', { class: 'cell-sub mono' }, `${token.prefix}…`)] },
        { label: 'Created', cell: (token) => timeCell(token.createdAt) },
        { label: 'Last used', cell: (token) => timeCell(token.lastUsedAt) },
        { label: 'Expires', cell: (token) => (token.expiresAt ? timeCell(token.expiresAt) : 'never') },
        {
          label: '',
          class: 'actions',
          cell: (token) => actionButton('Revoke', async () => {
            await del(`/api/v1/me/tokens/${segment(token.id)}`);
            toast(`Token ${token.name} revoked.`, 'ok');
            await loadTokens();
          }, { small: true }),
        },
      ], tokens.filter((token) => !token.revokedAt), { empty: 'No personal tokens. Create one for mshctl, a script or an agent.' }));
    };

    const createToken = async () => {
      const issued = await showDialog({
        title: 'Create personal token',
        body: [
          field('Name', input('name', { required: true, placeholder: 'e.g. laptop mshctl, claude agent', maxlength: 100 })),
          field('Expires', select('expires', EXPIRY, '90')),
        ],
        submitLabel: 'Create token',
        onSubmit: (data) => post('/api/v1/me/tokens', { name: data.get('name').trim(), expiresInDays: data.get('expires') ? Number(data.get('expires')) : null }),
      });
      if (!issued) return;
      await loadTokens();
      await showSecret({
        title: `Token ${issued.token.name}`,
        message: 'It acts as you, with your role and limits, until it expires or you revoke it.',
        secret: issued.secret,
        usage: `export MSH_URL=${window.location.origin}\nexport MSH_TOKEN=${issued.secret}\nmshctl whoami`,
      });
    };

    const [session, me] = await Promise.all([get('/api/v1/auth/session'), ctx.refreshMe()]);
    if (!ctx.isCurrent()) return;
    const user = session.user;
    mount(main,
      pageHeader('Your account', 'How you sign in, what you may hold, and tokens that act as you.'),
      h('div', { class: 'columns' },
        card('Account', details([
          ['Username', user.username],
          ['Name', user.displayName],
          ['Email', user.email],
          ['Role', [stateBadge(user.role), user.roleManagedByProvider ? ` from ${user.provider} groups` : '']],
          ['Signs in with', user.source === 'oidc' ? `OIDC (${user.provider})` : user.source === 'ldap' ? 'LDAP' : 'Password'],
          ['Limits', quotaText(me.quota)],
          ['Holding now', `${plural(me.usage.activeSessions, 'session')}, ${plural(me.usage.devices, 'device')}`],
          ['Last sign-in', timeCell(user.lastLoginAt)],
        ])),
        passwordCard(user)),
      h('section', { class: 'card' },
        h('div', { class: 'card-header' },
          h('h2', { class: 'card-title' }, 'Personal API tokens'),
          button('Create token', () => createToken().catch(toastError), { kind: 'primary', small: true })),
        h('p', { class: 'muted small' }, 'Use them with mshctl, the Kotlin and Python clients, scripts and MCP. mshctl login creates one for you.'),
        tokensBody));
    await loadTokens();
  },
};
