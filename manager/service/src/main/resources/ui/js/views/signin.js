// Signing in, and replacing a one-time password before anything else.
import { get, post } from '../api.js';
import { field, h, input, mount } from '../dom.js';

export function brand() {
  return h('div', { class: 'brand' },
    h('img', { class: 'brand-logo', src: '/ui/favicon.svg', alt: '', width: 30, height: 30 }),
    h('span', { class: 'brand-name' }, 'Marathon Shepherd'));
}

export async function renderSignIn(root, { notice, onSignedIn }) {
  root.className = 'auth-page';
  const card = h('div', { class: 'auth-card' }, brand(), h('p', { class: 'muted' }, 'Loading sign-in options…'));
  mount(root, card);
  let methods;
  try {
    methods = await get('/api/v1/auth/methods', { quietUnauthorized: true });
  } catch (error) {
    mount(card, brand(), h('p', { class: 'form-error', role: 'alert' }, error.message),
      h('button', { type: 'button', class: 'button button-secondary', onClick: () => renderSignIn(root, { notice, onSignedIn }) }, 'Try again'));
    return;
  }
  const failure = methods.signInError ? `Sign-in failed: ${methods.signInError}` : null;
  const alert = h('p', { class: failure ? 'form-error' : 'notice', role: 'alert', hidden: !(failure ?? notice) }, failure ?? notice ?? '');
  const passwordForm = methods.local || methods.ldap ? buildPasswordForm(alert, onSignedIn) : null;
  const providers = methods.providers ?? [];
  // After an OIDC sign-in, come back to the page that asked for it.
  const hash = window.location.hash && !window.location.hash.startsWith('#/sign-in') ? window.location.hash : '';
  const returnTo = encodeURIComponent(`/ui/${hash}`);
  mount(card,
    brand(),
    h('h1', { class: 'auth-title' }, 'Sign in'),
    alert,
    passwordForm,
    providers.length ? h('div', { class: 'providers' },
      passwordForm && h('div', { class: 'divider' }, 'or'),
      providers.map((provider) => h('a', { class: 'button button-secondary button-block', href: `${provider.loginUrl}?returnTo=${returnTo}` },
        `Sign in with ${provider.displayName}`))) : null,
    !passwordForm && !providers.length ? h('p', {}, 'No sign-in method is enabled. Ask an administrator to configure one.') : null);
  card.querySelector('input')?.focus();
}

function buildPasswordForm(alert, onSignedIn) {
  const submit = h('button', { type: 'submit', class: 'button button-primary button-block' }, 'Sign in');
  const form = h('form', { class: 'auth-form' },
    field('Username', input('username', { autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false', required: true })),
    field('Password', input('password', { type: 'password', autocomplete: 'current-password', required: true })),
    submit);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const data = new FormData(form);
    submit.disabled = true;
    submit.textContent = 'Signing in…';
    alert.hidden = true;
    try {
      const session = await post('/api/v1/auth/login', { username: data.get('username'), password: data.get('password') }, { quietUnauthorized: true });
      await onSignedIn(session);
    } catch (error) {
      alert.className = 'form-error';
      alert.textContent = error.message;
      alert.hidden = false;
      form.elements.password.value = '';
      form.elements.password.focus();
    } finally {
      submit.disabled = false;
      submit.textContent = 'Sign in';
    }
  });
  return form;
}

export function renderAccountSetUp(root, { user, onDone, onSignOut }) {
  root.className = 'auth-page';
  // Nobody owns the account the manager made at first start yet: whoever finishes this form
  // takes it over and picks the name they will sign in with.
  const claiming = user.unclaimed === true;
  const alert = h('p', { class: 'form-error', role: 'alert', hidden: true });
  const submit = h('button', { type: 'submit', class: 'button button-primary button-block' },
    claiming ? 'Create my account' : 'Save my password');
  const form = h('form', { class: 'auth-form' },
    claiming ? field('Username',
      input('username', { value: user.username, autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false', required: true }),
      'The name you sign in with from now on. Change it to your own.') : null,
    claiming ? field('Display name',
      input('displayName', { value: user.displayName ?? '', autocomplete: 'name' }),
      'Optional. Shown next to everything you do.') : null,
    field('New password', input('next', { type: 'password', autocomplete: 'new-password', required: true }),
      'A long passphrase works best. It must not be your username.'),
    field('New password again', input('repeat', { type: 'password', autocomplete: 'new-password', required: true })),
    submit);
  const fail = (message) => {
    alert.textContent = message;
    alert.hidden = false;
  };
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const data = new FormData(form);
    if (data.get('next') !== data.get('repeat')) {
      fail('The new passwords do not match.');
      return;
    }
    submit.disabled = true;
    alert.hidden = true;
    try {
      // The password that got us here is the one being replaced, so it is not asked for again.
      const account = await post('/api/v1/me/setup', {
        username: claiming ? data.get('username') : undefined,
        displayName: claiming ? data.get('displayName') : undefined,
        newPassword: data.get('next'),
      }, { quietUnauthorized: true });
      await onDone(account);
    } catch (error) {
      fail(error.message);
    } finally {
      submit.disabled = false;
    }
  });
  mount(root, h('div', { class: 'auth-card' },
    brand(),
    h('h1', { class: 'auth-title' }, claiming ? 'Create your administrator account' : 'Choose your password'),
    h('p', { class: 'muted' }, claiming
      ? 'This manager has no accounts yet. Take this one over: pick the name you will sign in with and a password of your own.'
      : `Welcome, ${user.displayName ?? user.username}. Replace the password you were given to continue.`),
    alert,
    form,
    h('button', { type: 'button', class: 'button button-ghost button-block', onClick: onSignOut }, 'Sign out')));
  form.querySelector('input')?.focus();
}
