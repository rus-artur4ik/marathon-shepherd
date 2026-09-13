// Live activity: session, device and provider events as they happen.
import { badge, button, emptyState, field, h, mount, pageHeader, select } from '../dom.js';
import { dateTime } from '../format.js';
import { describeEvent, onLiveEvent, recentEvents } from '../live.js';

const MAX_ITEMS = 500;

function tone(type) {
  if (type === 'session.ready' || type === 'provider.up' || type === 'provider.registered') return 'ok';
  if (type === 'session.failed' || type === 'provider.down') return 'bad';
  if (type === 'device.maintenance' || type === 'lease.reclaimed') return 'warn';
  if (type === 'session.created' || type === 'session.extended') return 'info';
  return 'muted';
}

/** One line of the feed; the overview shows the same lines. */
export function feedItem(event) {
  return h('li', { class: 'feed-item' },
    h('time', { class: 'feed-time', datetime: event.at, title: dateTime(event.at) }, new Date(event.at).toLocaleTimeString()),
    h('span', { class: 'feed-type' }, badge(event.type, tone(event.type))),
    h('span', {}, describeEvent(event)));
}

export default {
  title: 'Live activity',

  mount(main) {
    let filter = '';
    let paused = false;
    const held = [];
    const list = h('ul', { class: 'feed' });
    const empty = emptyState('Waiting for events. They appear here as sessions start and end, devices change and providers come and go.');
    const matches = (event) => !filter || event.type.startsWith(filter);
    const sync = () => {
      empty.hidden = list.children.length > 0;
    };
    const add = (event) => {
      if (!matches(event)) return;
      list.prepend(feedItem(event));
      while (list.children.length > MAX_ITEMS) list.lastElementChild.remove();
      sync();
    };
    const refill = () => {
      list.replaceChildren(...recentEvents().filter(matches).map(feedItem));
      sync();
    };
    const pauseButton = button('Pause', () => {
      paused = !paused;
      if (!paused) held.splice(0).forEach(add);
      pauseButton.textContent = paused ? 'Resume' : 'Pause';
    });
    const clearButton = button('Clear', () => {
      list.replaceChildren();
      sync();
    });
    const typeSelect = select('type', [['', 'All events'], ['session.', 'Sessions'], ['device.', 'Devices'], ['provider.', 'Providers'], ['lease.', 'Leases']], '');
    typeSelect.addEventListener('change', () => {
      filter = typeSelect.value;
      refill();
    });
    const stop = onLiveEvent((event) => {
      if (paused) {
        held.push(event);
        pauseButton.textContent = `Resume (${held.length} new)`;
      } else {
        add(event);
      }
    });
    refill();
    mount(main,
      pageHeader('Live activity', 'Events since you opened the UI. The audit log keeps the full history.', pauseButton, clearButton),
      h('div', { class: 'toolbar' }, field('Show', typeSelect)),
      h('section', { class: 'card' }, empty, list));
    return stop;
  },
};
