// The web UI's shell: who is signed in, the navigation, and which view shows.
import { get, onSessionProblem, post, setCsrfToken } from './api.js';
import { emptyState, errorState, h, loading, mount, pageHeader, stateBadge, toast } from './dom.js';
import { refreshRelativeTimes } from './format.js';
import { onLiveState, startLive, stopLive } from './live.js';
import activity from './views/activity.js';
import audit from './views/audit.js';
import clients from './views/clients.js';
import config from './views/config.js';
import devices from './views/devices.js';
import overview from './views/overview.js';
import profile from './views/profile.js';
import providers from './views/providers.js';
import sessions from './views/sessions.js';
import { renderAccountSetUp, renderSignIn } from './views/signin.js';
import users from './views/users.js';

const ROUTES = [
  { path: '/', view: overview, section: 'Fleet' },
  { path: '/devices', view: devices, section: 'Fleet' },
  { path: '/sessions', view: sessions, section: 'Fleet' },
  { path: '/providers', view: providers, section: 'Fleet' },
  { path: '/activity', view: activity, section: 'Fleet' },
  { path: '/users', view: users, section: 'Administration', adminOnly: true },
  { path: '/clients', view: clients, section: 'Administration', adminOnly: true },
  { path: '/audit', view: audit, section: 'Administration', adminOnly: true },
  { path: '/config', view: config, section: 'Administration', adminOnly: true },
  { path: '/profile', view: profile, section: 'You' },
];
const LIVE_LABELS = { connecting: 'Connecting', live: 'Live', reconnecting: 'Reconnecting', closed: 'Offline' };

const app = document.getElementById('app');
const state = { user: null, me: null, version: null };
let shell = null;
let disposeView = null;
let renderToken = 0;
let liveWasOpen = false;
let stopLiveState = null;

onSessionProblem((error) => {
  if (!state.user) {
    return;
  }
  if (error.status === 401) {
    showSignIn('Your session ended. Sign in again.');
  } else {
    state.user = { ...state.user, mustChangePassword: true };
    showAccountSetUp();
  }
});

window.addEventListener('hashchange', route);
get('/live').then((live) => {
  state.version = live.version;
  if (shell) {
    shell.version.textContent = `v${live.version}`;
  }
}).catch(() => {});
setInterval(refreshRelativeTimes, 30000);
boot();

async function boot() {
  try {
    await signedIn(await get('/api/v1/auth/session', { quietUnauthorized: true }));
  } catch (error) {
    if (error.status === 401 || error.status === 404) {
      showSignIn();
    } else {
      mount(app, errorState(error, () => window.location.reload()));
    }
  }
}

async function signedIn(webSession) {
  setCsrfToken(webSession.csrfToken);
  state.user = webSession.user;
  if (state.user.mustChangePassword) {
    showAccountSetUp();
    return;
  }
  state.me = await get('/api/v1/me').catch(() => null);
  renderShell();
  startLive();
  const target = !window.location.hash || window.location.hash.startsWith('#/sign-in') ? '#/' : window.location.hash;
  if (window.location.hash === target) {
    route();
  } else {
    window.location.hash = target;
  }
}

function showSignIn(notice) {
  resetShell();
  state.user = null;
  setCsrfToken(null);
  renderSignIn(app, { notice, onSignedIn: signedIn });
}

function showAccountSetUp() {
  resetShell();
  const claiming = state.user?.unclaimed === true;
  renderAccountSetUp(app, {
    user: state.user,
    onDone: async (account) => {
      await signedIn(await get('/api/v1/auth/session'));
      toast(claiming ? `Welcome, ${account.username}. The account is yours.` : 'Your password is changed.', 'ok');
    },
    onSignOut: signOut,
  });
}

async function signOut() {
  try {
    await post('/api/v1/auth/logout', undefined, { quietUnauthorized: true });
  } catch {
    // Signed out either way.
  }
  window.history.replaceState(null, '', '#/sign-in');
  showSignIn('You are signed out.');
}

function resetShell() {
  disposeCurrentView();
  stopLiveState?.();
  stopLiveState = null;
  stopLive();
  liveWasOpen = false;
  shell = null;
}

const isAdmin = () => state.user?.role === 'admin';

function renderShell() {
  const liveIndicator = h('span', { class: 'live-indicator live-closed' }, LIVE_LABELS.closed);
  const version = h('span', {}, state.version ? `v${state.version}` : '');
  const sections = [...new Set(ROUTES.map((entry) => entry.section))];
  const nav = h('nav', { class: 'nav', 'aria-label': 'Main' }, sections.map((section) => {
    const links = ROUTES.filter((entry) => entry.section === section && (!entry.adminOnly || isAdmin()));
    return links.length ? h('div', { class: 'nav-section' },
      h('p', { class: 'nav-heading' }, section),
      links.map((entry) => h('a', { class: 'nav-link', href: `#${entry.path}`, dataset: { path: entry.path } }, entry.view.title))) : null;
  }));
  const main = h('main', { class: 'main', id: 'main', tabindex: '-1' });
  const user = state.user;
  const sidebar = h('aside', { class: 'sidebar' },
    h('a', { class: 'brand', href: '#/' }, h('img', { class: 'brand-logo', src: '/ui/favicon.svg', alt: '', width: 26, height: 26 }), h('span', { class: 'brand-name' }, 'Marathon Shepherd')),
    nav,
    h('div', { class: 'sidebar-footer' },
      h('div', { class: 'sidebar-user' }, h('a', { href: '#/profile' }, user.displayName ?? user.username), stateBadge(user.role)),
      h('button', { type: 'button', class: 'button button-secondary button-small', onClick: signOut }, 'Sign out'),
      h('div', { class: 'sidebar-meta' }, liveIndicator, version)));
  app.className = '';
  mount(app, h('div', { class: 'layout' }, sidebar, main));
  shell = { main, nav, version };
  stopLiveState?.();
  stopLiveState = onLiveState((live) => {
    if (!shell) {
      return;
    }
    liveIndicator.className = `live-indicator live-${live}`;
    liveIndicator.textContent = LIVE_LABELS[live] ?? live;
    if (live === 'live') {
      liveWasOpen = true;
    } else if (live === 'closed' && liveWasOpen && state.user) {
      // The stream stops for good when the manager refuses it; find out whether the session is gone.
      liveWasOpen = false;
      get('/api/v1/auth/session').catch(() => {});
    }
  });
}

function parseHash() {
  const raw = window.location.hash.replace(/^#/, '') || '/';
  const [path, search = ''] = raw.split('?');
  return { path: path || '/', params: new URLSearchParams(search) };
}

function disposeCurrentView() {
  const dispose = disposeView;
  disposeView = null;
  try {
    dispose?.();
  } catch {
    // A view that fails to clean up must not keep the next one from showing.
  }
}

function route() {
  if (!state.user || state.user.mustChangePassword || !shell) {
    return;
  }
  const { path, params } = parseHash();
  document.querySelectorAll('dialog[open]').forEach((dialog) => dialog.close());
  disposeCurrentView();
  shell.nav.querySelectorAll('.nav-link').forEach((link) => {
    if (link.dataset.path === path) {
      link.setAttribute('aria-current', 'page');
    } else {
      link.removeAttribute('aria-current');
    }
  });
  const entry = ROUTES.find((candidate) => candidate.path === path);
  if (!entry) {
    mount(shell.main, pageHeader('Page not found'), emptyState('There is no page here. Pick one from the menu.'));
    return;
  }
  if (entry.adminOnly && !isAdmin()) {
    mount(shell.main, pageHeader(entry.view.title), emptyState('Only admins can open this page.'));
    return;
  }
  document.title = `${entry.view.title} · Marathon Shepherd`;
  mount(shell.main, loading());
  const token = ++renderToken;
  const context = {
    user: state.user,
    me: state.me,
    isAdmin: isAdmin(),
    canHold: state.user.role === 'admin' || state.user.role === 'user',
    params,
    isCurrent: () => token === renderToken,
    navigate: (hash) => {
      window.location.hash = hash;
    },
    refreshMe: async () => {
      state.me = await get('/api/v1/me');
      return state.me;
    },
  };
  Promise.resolve()
    .then(() => entry.view.mount(shell.main, context))
    .then((dispose) => {
      if (token === renderToken) {
        disposeView = typeof dispose === 'function' ? dispose : null;
      } else if (typeof dispose === 'function') {
        dispose();
      }
    })
    .catch((error) => {
      if (token === renderToken && shell) {
        mount(shell.main, pageHeader(entry.view.title), errorState(error, route));
      }
    });
}

