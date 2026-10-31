// No external card can replace this script. Messages require same origin, parent and session.
const session = new URLSearchParams(location.search).get('session');
const channel = 'yingguang-card';
let lastState = '', announced = false, errorAnnounced = false;
const send = (type, payload) => {
  if (session && window.parent !== window) window.parent.postMessage({ channel, session, type, payload }, location.origin);
};
const snapshot = () => {
  const api = window.__libraryViewer;
  if (!api) return;
  const state = api.getState();
  const serialized = JSON.stringify(state);
  if (lastState !== serialized) { lastState = serialized; send('state', state); }
};
window.addEventListener('message', event => {
  if (event.origin !== location.origin || event.source !== window.parent) return;
  const data = event.data;
  if (!data || data.channel !== channel || data.session !== session || !session) return;
  const api = window.__libraryViewer;
  if (!api) return;
  if (data.type === 'parameters' && data.payload && typeof data.payload === 'object') api.setParameters(data.payload);
  if (data.type === 'auto' && typeof data.payload === 'boolean') api.setAuto(data.payload);
  if (data.type === 'flip' && typeof data.payload === 'boolean') api.flip(data.payload);
  if (data.type === 'layers' && typeof data.payload === 'boolean') api.setLayers(data.payload);
  if (data.type === 'layerGap' && Number.isFinite(data.payload)) api.setLayerGap(data.payload);
  if (data.type === 'rotationSensitivity' && Number.isFinite(data.payload)) api.setRotationSensitivity(data.payload);
  if (data.type === 'reset') api.reset();
  snapshot();
});
// Iframe key events do not bubble to the library. Leave native fullscreen,
// editable controls and modal cancellation to their existing handlers.
window.addEventListener('keydown', event => {
  if (event.key !== 'Escape' || event.repeat || event.defaultPrevented || document.fullscreenElement || document.querySelector('dialog[open]')) return;
  const target = event.target;
  if (target instanceof Element && target.closest('input,select,textarea,[contenteditable]:not([contenteditable="false"])')) return;
  if (!session || !window.__libraryViewer) return;
  event.preventDefault();
  send('escape');
});
const monitor = setInterval(() => {
  const api = window.__libraryViewer;
  if (api && !announced) {
    announced = true;
    send('ready', { defaults: api.defaults, ranges: api.ranges, hasEffects: api.hasEffects, mode: api.mode });
  }
  if (!api && window.__holo?.error && !window.__holo?.fallback3d && !errorAnnounced) {
    errorAnnounced = true;
    send('error', { message: window.__holo.error });
  }
  if (!document.hidden) snapshot();
}, 250);
window.addEventListener('pagehide', () => clearInterval(monitor));
