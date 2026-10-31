import { cleanSettings, settingsDefaults } from './store.js';

let current = cleanSettings();

export function renderSettings(settings) {
  current = cleanSettings(settings);
  const input = document.getElementById('rotation-sensitivity');
  if (!input) return;
  const value = current.rotationSensitivity;
  input.value = String(value);
  input.style.setProperty('--range', `${(value - .25) / 1.75 * 100}%`);
  input.setAttribute('aria-valuetext', `${value.toFixed(2)} 倍`);
  document.getElementById('rotation-sensitivity-value').value = `${value.toFixed(2)}×`;
}

// State persistence and viewer updates belong to the application. The callback
// receives the complete normalized global settings, including reset changes.
export function initSettings(onChange) {
  const trigger = document.getElementById('settings-open');
  const dialog = document.getElementById('settings-dialog');
  const input = document.getElementById('rotation-sensitivity');
  const closeButton = document.getElementById('settings-close');
  const resetButton = document.getElementById('settings-reset');
  let returnFocus = trigger;
  const update = settings => {
    renderSettings(settings);
    if (typeof onChange === 'function') onChange({ ...current });
  };
  const open = () => {
    if (dialog.open) return;
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : trigger;
    dialog.showModal();
    trigger.setAttribute('aria-expanded', 'true');
    input.focus({ preventScroll: true });
  };
  const close = () => dialog.close();
  const restoreFocus = () => {
    trigger.setAttribute('aria-expanded', 'false');
    (returnFocus?.isConnected ? returnFocus : trigger).focus({ preventScroll: true });
  };
  const change = () => update({ rotationSensitivity: input.value });
  const reset = () => update(settingsDefaults);
  const trapFocus = event => {
    if (event.key !== 'Tab') return;
    const first = closeButton;
    const last = document.getElementById('backup');
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };
  trigger.addEventListener('click', open);
  closeButton.addEventListener('click', close);
  dialog.addEventListener('close', restoreFocus);
  dialog.addEventListener('keydown', trapFocus);
  input.addEventListener('input', change);
  resetButton.addEventListener('click', reset);
  renderSettings(current);
  return () => {
    trigger.removeEventListener('click', open);
    closeButton.removeEventListener('click', close);
    dialog.removeEventListener('close', restoreFocus);
    dialog.removeEventListener('keydown', trapFocus);
    input.removeEventListener('input', change);
    resetButton.removeEventListener('click', reset);
  };
}
