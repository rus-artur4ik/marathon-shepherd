// The manager's JSON API, called from a signed-in browser. The session rides on an HttpOnly
// cookie; every change also carries the session's CSRF token, which only this page can read.

let csrfToken = null;
const problemListeners = new Set();

const PASSWORD_REQUIRED = 'Choose a new password first';

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export function setCsrfToken(token) {
  csrfToken = token;
}

/** Called when a request finds the session gone (401) or a new password required (403). */
export function onSessionProblem(listener) {
  problemListeners.add(listener);
}

/**
 * Sends a request and returns the parsed JSON body, or null for an empty one. Fails with an
 * ApiError carrying the manager's own message. `quietUnauthorized` is for calls where a 401
 * means "wrong password" rather than "signed out".
 */
export async function request(method, path, { body, quietUnauthorized = false } = {}) {
  const headers = { Accept: 'application/json' };
  const init = { method, headers, credentials: 'same-origin', cache: 'no-store' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  if (method !== 'GET' && csrfToken) {
    headers['X-CSRF-Token'] = csrfToken;
  }
  let response;
  try {
    response = await fetch(path, init);
  } catch {
    throw new ApiError(0, 'Cannot reach the manager. Check the connection and try again.');
  }
  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (response.ok) {
    return data;
  }
  const message = data && typeof data === 'object' && typeof data.error === 'string'
    ? data.error
    : `${response.status} ${response.statusText || 'error'}`;
  const error = new ApiError(response.status, message);
  const signedOut = response.status === 401 && !quietUnauthorized;
  const passwordRequired = response.status === 403 && message.startsWith(PASSWORD_REQUIRED);
  if (signedOut || passwordRequired) {
    problemListeners.forEach((listener) => listener(error));
  }
  throw error;
}

export const get = (path, options = {}) => request('GET', path, options);
export const post = (path, body, options = {}) => request('POST', path, { ...options, body });
export const put = (path, body, options = {}) => request('PUT', path, { ...options, body });
export const patch = (path, body, options = {}) => request('PATCH', path, { ...options, body });
export const del = (path, options = {}) => request('DELETE', path, options);

/** `?a=1&b=2` from the values that are set; arrays repeat their key. */
export function query(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '' || value === false) {
      continue;
    }
    if (Array.isArray(value)) {
      value.forEach((item) => search.append(key, item));
    } else {
      search.append(key, String(value));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

/** One escaped path segment: device ids contain ':' and names may contain anything. */
export const segment = (value) => encodeURIComponent(value);
