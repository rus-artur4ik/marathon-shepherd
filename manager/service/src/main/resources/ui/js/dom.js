// A small DOM toolkit. Every string becomes a text node and no markup is ever parsed from a
// string, so nothing the manager returns can inject HTML into the page.

const SAFE_URL = /^(\/(?!\/)|#|https?:\/\/)/;
const PROPERTIES = new Set(['value', 'checked', 'selected', 'disabled', 'hidden', 'readOnly']);

/** Creates an element. Children are nodes, strings, numbers or arrays of them; null and false are skipped. */
export function h(tag, props = {}, ...children) {
  const element = document.createElement(tag);
  for (const [key, value] of Object.entries(props ?? {})) {
    if (value === undefined || value === null || value === false) {
      continue;
    }
    if (key === 'class') {
      element.className = Array.isArray(value) ? value.filter(Boolean).join(' ') : value;
    } else if (key === 'dataset') {
      Object.assign(element.dataset, value);
    } else if (key.startsWith('on') && typeof value === 'function') {
      element.addEventListener(key.slice(2).toLowerCase(), value);
    } else if (key === 'style') {
      // The Content-Security-Policy refuses inline styles; use a class.
    } else if ((key === 'href' || key === 'src') && !SAFE_URL.test(String(value))) {
      // Only links within this manager, fragments and http(s) addresses.
    } else if (PROPERTIES.has(key) && tag !== 'select') {
      element[key] = value;
    } else if (key !== 'value') {
      element.setAttribute(key, value === true ? '' : String(value));
    }
  }
  append(element, children);
  // A select can only take its value once its options exist.
  if (tag === 'select' && props?.value !== undefined && props?.value !== null) {
    element.value = String(props.value);
  }
  return element;
}

export function append(element, children) {
  for (const child of [children].flat(Infinity)) {
    if (child === null || child === undefined || child === false) {
      continue;
    }
    element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return element;
}

const signatures = new WeakMap();

/** Replaces everything inside `element`. */
export function mount(element, ...children) {
  signatures.delete(element);
  element.replaceChildren();
  return append(element, children);
}

/**
 * Re-renders `element` only when `data` differs from what it last showed, so a refresh that finds
 * nothing new keeps focus, hover and scrolling where they are. `build` returns the children.
 */
export function mountIfChanged(element, data, build) {
  const signature = JSON.stringify(data) ?? 'undefined';
  if (signatures.get(element) === signature) {
    return false;
  }
  mount(element, build());
  signatures.set(element, signature);
  return true;
}

const TONES = {
  available: 'ok', ready: 'ok', healthy: 'ok', success: 'ok', active: 'ok', live: 'ok',
  busy: 'info', pending: 'info', user: 'info', connecting: 'info', reconnecting: 'warn',
  maintenance: 'warn', denied: 'warn', 'must change password': 'warn',
  offline: 'bad', failed: 'bad', unhealthy: 'bad', down: 'bad', closed: 'bad',
  released: 'muted', expired: 'muted', disabled: 'muted', revoked: 'muted', inactive: 'muted',
  admin: 'accent',
};

export function badge(text, tone = 'neutral', title) {
  return h('span', { class: `badge badge-${tone}`, title }, text);
}

/** A badge whose colour follows the word: available is green, offline red, and so on. */
export function stateBadge(state, title) {
  const text = String(state ?? 'unknown').toLowerCase();
  return badge(text, TONES[text] ?? 'neutral', title);
}

export function button(label, onClick, { kind = 'secondary', small = false, title, type = 'button', disabled = false } = {}) {
  return h('button', { type, class: ['button', `button-${kind}`, small && 'button-small'], title, disabled, onClick }, label);
}

/** A button that runs an async action, disabled while it runs; failures become a toast. */
export function actionButton(label, action, options = {}) {
  const element = button(label, async (event) => {
    event.stopPropagation();
    element.disabled = true;
    try {
      await action(event);
    } catch (error) {
      toastError(error);
    } finally {
      element.disabled = false;
    }
  }, options);
  return element;
}

/** Rows as a table. `columns` are `{ label, cell(row), class }`. */
export function table(columns, rows, { empty = 'Nothing here yet.', rowClass } = {}) {
  if (!rows.length) {
    return emptyState(empty);
  }
  return h('div', { class: 'table-wrap' },
    h('table', { class: 'table' },
      h('thead', {}, h('tr', {}, columns.map((column) => h('th', { scope: 'col', class: column.class }, column.label)))),
      h('tbody', {}, rows.map((row) => h('tr', { class: rowClass?.(row) },
        columns.map((column) => h('td', { class: column.class, 'data-label': column.label }, column.cell(row))))))));
}

export function pageHeader(title, description, ...actions) {
  return h('header', { class: 'page-header' },
    h('div', { class: 'page-heading' }, h('h1', {}, title), description && h('p', { class: 'page-description' }, description)),
    h('div', { class: 'page-actions' }, actions));
}

export function card(title, ...content) {
  return h('section', { class: 'card' }, title && h('h2', { class: 'card-title' }, title), content);
}

export const emptyState = (text) => h('div', { class: 'empty' }, text);
export const loading = (text = 'Loading…') => h('div', { class: 'loading', 'aria-busy': 'true' }, text);

export function errorState(error, retry) {
  return h('div', { class: 'error-state', role: 'alert' },
    h('p', {}, error?.message ?? String(error)),
    retry && button('Try again', retry));
}

/** `key=value` chips for labels and metadata. */
export function chips(map) {
  const entries = Object.entries(map ?? {});
  return entries.length ? h('span', { class: 'chips' }, entries.map(([key, value]) => h('span', { class: 'chip' }, `${key}=${value}`))) : '—';
}

export const code = (text) => h('code', { class: 'code-inline' }, text);

/** Term/value pairs; empty values are left out. */
export function details(pairs) {
  return h('dl', { class: 'details' }, pairs
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .flatMap(([term, value]) => [h('dt', {}, term), h('dd', {}, value)]));
}

export function field(label, control, hint) {
  return h('label', { class: 'field' },
    h('span', { class: 'field-label' }, label),
    control,
    hint && h('span', { class: 'field-hint' }, hint));
}

export function input(name, attributes = {}) {
  return h('input', { class: 'input', name, type: 'text', ...attributes });
}

/** `options` are `[value, label]` pairs. */
export function select(name, options, value, attributes = {}) {
  return h('select', { class: 'input', name, value, ...attributes }, options.map(([optionValue, label]) => h('option', { value: optionValue }, label)));
}

export function checkbox(name, label, checked = false) {
  return h('label', { class: 'checkbox' }, h('input', { type: 'checkbox', name, checked }), h('span', {}, label));
}

export function copyButton(text, label = 'Copy') {
  const element = button(label, async () => {
    try {
      await navigator.clipboard.writeText(text);
      element.textContent = 'Copied';
    } catch {
      element.textContent = 'Select and copy';
    }
    setTimeout(() => {
      element.textContent = label;
    }, 1600);
  }, { small: true });
  return element;
}

export function toast(message, tone = 'info') {
  const host = document.getElementById('toasts');
  const item = h('div', { class: `toast toast-${tone}` }, message);
  host.append(item);
  setTimeout(() => item.classList.add('toast-leaving'), 4200);
  setTimeout(() => item.remove(), 4800);
}

export const toastError = (error) => toast(error?.message ?? String(error), 'bad');

/**
 * A modal form. `onSubmit(formData, form)` runs on submit; if it throws, its message shows in the
 * dialog, which stays open. Resolves with what `onSubmit` returned (true without one), or
 * undefined when cancelled.
 */
export function showDialog({ title, body, submitLabel = 'Save', submitKind = 'primary', cancelLabel = 'Cancel', onSubmit, wide = false }) {
  return new Promise((resolve) => {
    const errorLine = h('p', { class: 'form-error', role: 'alert', hidden: true });
    const submit = h('button', { type: 'submit', class: `button button-${submitKind}` }, submitLabel);
    const form = h('form', { class: 'dialog-form' },
      h('h2', { class: 'dialog-title' }, title),
      h('div', { class: 'dialog-body' }, body),
      errorLine,
      h('div', { class: 'dialog-actions' },
        cancelLabel && h('button', { type: 'button', class: 'button button-secondary', onClick: () => finish(undefined) }, cancelLabel),
        submit));
    const dialog = h('dialog', { class: wide ? 'dialog dialog-wide' : 'dialog' }, form);
    let finished = false;
    function finish(value) {
      if (finished) {
        return;
      }
      finished = true;
      dialog.close();
      dialog.remove();
      resolve(value);
    }
    // Closed from outside, e.g. when the page changes behind it.
    dialog.addEventListener('close', () => finish(undefined));
    dialog.addEventListener('cancel', (event) => {
      event.preventDefault();
      finish(undefined);
    });
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      errorLine.hidden = true;
      submit.disabled = true;
      try {
        const value = onSubmit ? await onSubmit(new FormData(form), form) : true;
        finish(value === undefined ? true : value);
      } catch (error) {
        errorLine.textContent = error?.message ?? String(error);
        errorLine.hidden = false;
      } finally {
        submit.disabled = false;
      }
    });
    document.body.append(dialog);
    dialog.showModal();
    form.querySelector('input:not([type=hidden]):not([readonly]), select, textarea')?.focus();
  });
}

export function confirmAction({ title, message, confirmLabel = 'Confirm', danger = false, extra, onSubmit }) {
  return showDialog({ title, body: [h('p', {}, message), extra], submitLabel: confirmLabel, submitKind: danger ? 'danger' : 'primary', onSubmit });
}

/** Shows a key or password the manager will never show again. */
export function showSecret({ title, message, secret, usage }) {
  const value = h('input', { class: 'input secret-value', type: 'text', readOnly: true, value: secret, 'aria-label': title, spellcheck: 'false' });
  value.addEventListener('focus', () => value.select());
  return showDialog({
    title,
    body: [
      h('p', {}, message),
      h('div', { class: 'secret' }, value, copyButton(secret)),
      h('p', { class: 'field-hint' }, 'Shown only this once. Store it now: later only its first characters are visible.'),
      usage && h('pre', { class: 'code-block' }, usage),
    ],
    submitLabel: 'Done',
    cancelLabel: null,
  });
}
