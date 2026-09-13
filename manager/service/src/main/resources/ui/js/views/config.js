// Configuration: the running msh.yaml as JSON, editable by admins.
import { get, post, put } from '../api.js';
import { actionButton, confirmAction, h, mount, pageHeader, toast } from '../dom.js';

export default {
  title: 'Configuration',

  async mount(main, ctx) {
    const editor = h('textarea', { class: 'input', name: 'config', spellcheck: 'false', 'aria-label': 'Configuration as JSON' });
    const show = (config) => {
      editor.value = JSON.stringify(config, null, 2);
    };

    const save = async () => {
      let parsed;
      try {
        parsed = JSON.parse(editor.value);
      } catch (error) {
        throw new Error(`This is not valid JSON: ${error.message}`);
      }
      const confirmed = await confirmAction({
        title: 'Apply this configuration?',
        message: 'It takes effect immediately and is written back to msh.yaml. Providers with active sessions cannot be removed.',
        confirmLabel: 'Apply',
      });
      if (!confirmed) return;
      show(await put('/api/v1/config', parsed));
      toast('Configuration applied.', 'ok');
    };

    const reload = async () => {
      show(await post('/api/v1/config/reload'));
      toast('Reloaded msh.yaml from disk.', 'ok');
    };

    show(await get('/api/v1/config'));
    if (!ctx.isCurrent()) return;
    mount(main,
      pageHeader('Configuration', 'The configuration the manager runs with. Secrets show as <redacted>; leave them as they are to keep them.',
        actionButton('Reload from msh.yaml', reload),
        actionButton('Apply changes', save, { kind: 'primary' })),
      h('section', { class: 'card' }, editor));
  },
};
