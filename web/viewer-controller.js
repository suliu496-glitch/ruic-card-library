import { createImageCard, normalizeRotationSensitivity } from './image-card.js';
import { cleanParameters, defaults } from './store.js';
export class ViewerController {
  constructor(container, callbacks) {
    this.container = container;
    this.callbacks = callbacks;
    this.ready = false;
    this.rotationSensitivity = 1;
    this.messageHandler = event => this.receive(event);
    window.addEventListener('message', this.messageHandler);
  }
  open(card, parameters, auto) {
    this.close();
    this.card = card;
    this.parameters = parameters;
    this.auto = auto;
    this.flipped = false;
    this.layered = false;
    this.layerGap = 1;
    if (card.kind === 'image') {
      this.image = createImageCard(this.container, card, state => this.callbacks.state(state));
      this.image.setRotationSensitivity(this.rotationSensitivity);
      this.ready = true;
      this.image.parameters(parameters || defaults);
      this.image.auto(auto);
      this.callbacks.ready({ defaults: { ...defaults }, mode: 'image', hasEffects: false });
      return;
    }
    this.session = crypto.randomUUID();
    const iframe = document.createElement('iframe');
    const url = new URL('./viewer/index.html', document.baseURI);
    url.searchParams.set('config', card.configUrl);
    url.searchParams.set('session', this.session);
    iframe.src = url.href;
    iframe.title = `${card.title} · 交互式分层闪卡`;
    iframe.setAttribute('allow', 'fullscreen');
    this.iframe = iframe;
    this.container.replaceChildren(iframe);
    this.timeout = setTimeout(() => this.callbacks.error('这张卡加载时间较长。可重新加载，或切换到其他卡片。'), 20000);
  }
  receive(event) {
    if (event.origin !== location.origin || event.source !== this.iframe?.contentWindow) return;
    const data = event.data;
    if (!data || data.channel !== 'yingguang-card' || data.session !== this.session) return;
    if (data.type === 'ready') {
      clearTimeout(this.timeout);
      this.ready = true;
      const cardDefaults = cleanParameters(data.payload?.defaults);
      this.callbacks.ready({ ...data.payload, defaults: cardDefaults });
      this.setRotationSensitivity(this.rotationSensitivity);
      this.setParameters(this.parameters || cardDefaults);
      if (this.flipped) this.send('flip', true);
      this.send('layerGap', this.layerGap);
      if (this.layered) this.send('layers', true);
      this.setAuto(this.auto);
    } else if (data.type === 'state') {
      this.auto = data.payload?.auto === true;
      this.flipped = data.payload?.flipped === true;
      this.layered = data.payload?.layered === true;
      this.layerGap = Number(data.payload?.layerGap) || 1;
      this.callbacks.state({ auto: this.auto, flipped: this.flipped, layered: this.layered, layerGap: this.layerGap, rotationSensitivity: this.rotationSensitivity });
    } else if (data.type === 'escape' && this.ready) {
      this.callbacks.back?.();
    } else if (data.type === 'error') {
      clearTimeout(this.timeout);
      this.callbacks.error(String(data.payload?.message || '无法打开这张卡片。').slice(0, 300));
    }
  }
  send(type, payload) {
    this.iframe?.contentWindow?.postMessage({ channel: 'yingguang-card', session: this.session, type, payload }, location.origin);
  }
  setParameters(parameters) {
    this.parameters = cleanParameters(parameters);
    if (this.image) this.image.parameters(this.parameters);
    else if (this.ready) this.send('parameters', this.parameters);
  }
  setAuto(value) {
    this.auto = !!value;
    if (this.image) this.image.auto(value);
    else if (this.ready) this.send('auto', this.auto);
  }
  setRotationSensitivity(value) {
    this.rotationSensitivity = normalizeRotationSensitivity(value);
    if (this.image) this.image.setRotationSensitivity(this.rotationSensitivity);
    else if (this.ready) this.send('rotationSensitivity', this.rotationSensitivity);
    return this.rotationSensitivity;
  }
  flip(value) {
    this.flipped = !!value;
    this.auto = false;
    if (this.image) this.image.flip(value);
    else if (this.ready) this.send('flip', this.flipped);
  }
  setLayers(value) {
    if (this.card?.kind !== 'layered') return;
    this.layered = !!value;
    if (this.ready) this.send('layers', this.layered);
  }
  setLayerGap(value) {
    this.layerGap = Math.max(.25, Math.min(2, Number(value) || 1));
    if (this.ready && !this.image) this.send('layerGap', this.layerGap);
  }
  reset(parameters) {
    this.flipped = false;
    this.auto = false;
    if (this.image) this.image.reset();
    else if (this.ready) this.send('reset');
    this.setParameters(parameters);
  }
  close() {
    clearTimeout(this.timeout);
    this.image?.destroy();
    this.image = null;
    this.iframe = null;
    this.ready = false;
    this.container.replaceChildren();
  }
}
