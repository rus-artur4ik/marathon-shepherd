// Quota fields shared by the user and API client dialogs.
import { field, h, input } from '../dom.js';

/** Inputs for a quota; empty means not limited. */
export function quotaFields(quota = {}) {
  const hours = quota.maxSessionLifetimeSeconds ? String(quota.maxSessionLifetimeSeconds / 3600) : '';
  return h('div', { class: 'form-grid' },
    field('Devices at once', input('maxDevices', { type: 'number', min: 0, value: quota.maxDevices ?? '', placeholder: 'default' })),
    field('Longest session (hours)', input('maxLifetimeHours', { type: 'number', min: 0, step: 'any', value: hours, placeholder: 'default' })),
    field('Highest priority', input('maxPriority', { type: 'number', value: quota.maxPriority ?? '', placeholder: 'default' })));
}

/** The quota the fields describe, with empty fields as null. */
export function readQuota(data) {
  const number = (name) => {
    const text = String(data.get(name) ?? '').trim();
    return text === '' ? null : Number(text);
  };
  const hours = number('maxLifetimeHours');
  return {
    maxDevices: number('maxDevices'),
    maxSessionLifetimeSeconds: hours === null ? null : Math.round(hours * 3600),
    maxPriority: number('maxPriority'),
  };
}
