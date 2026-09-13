// Times, durations and quotas as people read them.
import { h } from './dom.js';

const RELATIVE = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
const UNITS = [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]];

export function relativeTime(iso) {
  const at = Date.parse(iso);
  if (!iso || Number.isNaN(at)) {
    return '—';
  }
  const seconds = Math.round((at - Date.now()) / 1000);
  const size = Math.abs(seconds);
  if (size < 45) {
    return 'just now';
  }
  const [unit, length] = UNITS.find(([, unitLength]) => size >= unitLength) ?? ['minute', 60];
  return RELATIVE.format(Math.round(seconds / length), unit);
}

export function dateTime(iso) {
  const at = Date.parse(iso);
  return !iso || Number.isNaN(at) ? '—' : new Date(at).toLocaleString();
}

/** A `<time>` that says "5 minutes ago" and shows the exact time on hover. */
export function timeCell(iso) {
  return iso ? h('time', { datetime: iso, title: dateTime(iso), dataset: { relative: '' } }, relativeTime(iso)) : '—';
}

/** Brings every relative time on the page up to date without rendering anything again. */
export function refreshRelativeTimes() {
  document.querySelectorAll('time[data-relative]').forEach((element) => {
    element.textContent = relativeTime(element.dateTime);
  });
}

export function duration(totalSeconds) {
  if (totalSeconds === undefined || totalSeconds === null) {
    return '—';
  }
  const seconds = Math.max(0, Math.round(Number(totalSeconds)));
  if (seconds < 60) {
    return `${seconds} s`;
  }
  if (seconds < 3600) {
    return `${Math.round(seconds / 60)} min`;
  }
  if (seconds < 86400) {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.round((seconds % 3600) / 60);
    return minutes ? `${hours} h ${minutes} min` : `${hours} h`;
  }
  const days = Math.floor(seconds / 86400);
  const hours = Math.round((seconds % 86400) / 3600);
  return hours ? `${days} d ${hours} h` : `${days} d`;
}

export function plural(count, one, many = `${one}s`) {
  const value = Number(count ?? 0);
  return `${value} ${value === 1 ? one : many}`;
}

export function quotaText(quota) {
  const parts = [];
  if (quota?.maxDevices !== undefined && quota?.maxDevices !== null) {
    parts.push(plural(quota.maxDevices, 'device'));
  }
  if (quota?.maxSessionLifetimeSeconds !== undefined && quota?.maxSessionLifetimeSeconds !== null) {
    parts.push(`sessions up to ${duration(quota.maxSessionLifetimeSeconds)}`);
  }
  if (quota?.maxPriority !== undefined && quota?.maxPriority !== null) {
    parts.push(`priority up to ${quota.maxPriority}`);
  }
  return parts.length ? parts.join(' · ') : 'unlimited';
}

/** `name (sess_…)` when the session has a name. */
export function sessionLabel(session) {
  return session.name ? `${session.name} (${session.id})` : session.id;
}

export function debounce(action, waitMs) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => action(...args), waitMs);
  };
}

/** `form=phone, rooted=false` → `{ form: 'phone', rooted: 'false' }`; throws on a malformed pair. */
export function parsePairs(text) {
  const pairs = {};
  for (const part of String(text ?? '').split(',').map((item) => item.trim()).filter(Boolean)) {
    const index = part.indexOf('=');
    if (index <= 0) {
      throw new Error(`Write labels as key=value, not "${part}"`);
    }
    pairs[part.slice(0, index).trim()] = part.slice(index + 1).trim();
  }
  return pairs;
}

/** How long a session may be kept, for the session dialogs. */
export const TTL_CHOICES = [
  ['1800', '30 minutes'], ['3600', '1 hour'], ['7200', '2 hours'], ['14400', '4 hours'], ['28800', '8 hours'], ['86400', '1 day'],
];
