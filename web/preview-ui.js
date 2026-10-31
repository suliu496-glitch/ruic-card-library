import { icon } from './icons.js';

// The controls stay mounted so changing screens never replaces slider state.
export function createPreviewUI(onChange) {
  const workspace = document.querySelector('.workspace');
  const panel = document.querySelector('.tuning');
  const mode = document.querySelector('.gallery-mode');
  panel.id = 'preview-parameters';
  let visible = false, opened = false;

  const toggle = document.createElement('button');
  toggle.id = 'parameters-toggle';
  toggle.className = 'parameters-toggle';
  toggle.type = 'button';
  toggle.setAttribute('aria-controls', panel.id);
  const adjustments = icon('settings');
  adjustments.innerHTML = '<path d="M4 7h4m4 0h8M4 17h8m4 0h4"/><circle cx="10" cy="7" r="2"/><circle cx="14" cy="17" r="2"/>';
  toggle.append(adjustments, document.createElement('span'));
  mode.append(toggle);

  const settings = document.createElement('button');
  settings.id = 'preview-settings';
  settings.type = 'button';
  settings.className = 'icon-button preview-settings';
  settings.setAttribute('aria-label', '打开观赏设置');
  settings.setAttribute('aria-haspopup', 'dialog');
  settings.setAttribute('aria-controls', 'settings-dialog');
  settings.title = '观赏设置';
  settings.append(icon('settings'));
  settings.onclick = () => document.getElementById('settings-open').click();
  mode.append(settings);

  const close = document.createElement('button');
  close.id = 'parameters-close';
  close.className = 'icon-button parameters-close';
  close.type = 'button';
  close.setAttribute('aria-label', '收起光影参数');
  close.title = '收起参数';
  close.append(icon('close'));
  const top = document.createElement('div');
  top.className = 'tuning-topline';
  top.append(panel.querySelector('.section-heading'), close);
  panel.prepend(top);

  function setOpen(value, { focus = true } = {}) {
    opened = visible && !!value;
    workspace.classList.toggle('parameters-open', opened);
    panel.hidden = !opened;
    panel.inert = !opened;
    panel.setAttribute('aria-hidden', String(!opened));
    toggle.setAttribute('aria-expanded', String(opened));
    toggle.lastElementChild.textContent = opened ? '收起参数' : '光影参数';
    if (focus && visible) (opened ? close : toggle).focus({ preventScroll: true });
    onChange?.(opened);
  }
  toggle.onclick = () => setOpen(!opened);
  close.onclick = () => setOpen(false);

  return {
    isOpen: () => opened,
    setOpen,
    setVisible(value) {
      visible = !!value;
      document.body.classList.toggle('preview-screen', visible);
      setOpen(false, { focus: false });
    },
  };
}
