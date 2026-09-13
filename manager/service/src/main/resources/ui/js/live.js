// One EventSource for the whole page, shared by every view that follows live changes.
import { dateTime, plural } from './format.js';

const TYPES = [
  'session.created', 'session.ready', 'session.released', 'session.expired', 'session.failed', 'session.extended',
  'device.maintenance', 'provider.up', 'provider.down', 'provider.registered', 'provider.deregistered', 'lease.reclaimed',
];
const HISTORY_SIZE = 200;

let source = null;
let state = 'closed';
const history = [];
const eventListeners = new Set();
const stateListeners = new Set();

export function startLive() {
  if (source) {
    return;
  }
  source = new EventSource('/api/v1/events');
  setState('connecting');
  source.addEventListener('open', () => setState('live'));
  // The browser reconnects by itself unless the manager refused the stream (a lost session, say).
  source.addEventListener('error', () => setState(source?.readyState === EventSource.CLOSED ? 'closed' : 'reconnecting'));
  TYPES.forEach((type) => source.addEventListener(type, receive));
}

export function stopLive() {
  source?.close();
  source = null;
  history.length = 0;
  setState('closed');
}

/** Calls `listener` for every event from now on; returns a function that stops it. */
export function onLiveEvent(listener) {
  eventListeners.add(listener);
  return () => eventListeners.delete(listener);
}

/** Calls `listener` with the connection state now and whenever it changes. */
export function onLiveState(listener) {
  stateListeners.add(listener);
  listener(state);
  return () => stateListeners.delete(listener);
}

/** Events received since the page loaded, newest first. */
export const recentEvents = () => [...history];

function receive(message) {
  let event;
  try {
    event = JSON.parse(message.data);
  } catch {
    return;
  }
  history.unshift(event);
  history.length = Math.min(history.length, HISTORY_SIZE);
  eventListeners.forEach((listener) => listener(event));
}

function setState(next) {
  state = next;
  stateListeners.forEach((listener) => listener(next));
}

const withReason = (text, reason) => (reason ? `${text}: ${reason}` : text);

/** One sentence about an event. */
export function describeEvent({ type, data = {} }) {
  const session = `${data.owner ? `${data.owner}'s session` : 'Session'} ${data.name ? `“${data.name}”` : data.sessionId}`;
  switch (type) {
    case 'session.created':
      return `${session} asked for ${plural(data.requestedDevices, 'device')}`;
    case 'session.ready':
      return `${session} is ready with ${plural(data.allocatedDevices, 'device')}`;
    case 'session.released':
      return withReason(`${session} was released`, data.reason);
    case 'session.expired':
      return `${session} expired`;
    case 'session.failed':
      return withReason(`${session} failed`, data.reason);
    case 'session.extended':
      return `${session} now lasts until ${dateTime(data.expiresAt)}`;
    case 'device.maintenance':
      return data.maintenance
        ? withReason(`${data.by} took ${data.deviceId} out for maintenance`, data.reason)
        : `${data.by} returned ${data.deviceId} to service`;
    case 'provider.up':
      return `Provider ${data.provider} is healthy`;
    case 'provider.down':
      return withReason(`Provider ${data.provider} is down`, data.error);
    case 'provider.registered':
      return `Provider ${data.provider} registered from ${data.url}`;
    case 'provider.deregistered':
      return `Provider ${data.provider} was removed`;
    case 'lease.reclaimed':
      return `Reclaimed lease ${data.leaseId} left behind on ${data.provider}`;
    default:
      return type;
  }
}
