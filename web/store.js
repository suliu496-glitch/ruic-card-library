export const STORE_KEY = 'yingguang-card-library-v1';
export const settingsDefaults = Object.freeze({ rotationSensitivity: 1 });
export function cleanSettings(input = {}) {
  const value = input && typeof input === 'object' && !Array.isArray(input) ? input.rotationSensitivity : undefined;
  const number = typeof value === 'number' || (typeof value === 'string' && value.trim()) ? Number(value) : NaN;
  return { rotationSensitivity: Number.isFinite(number) ? Math.round(Math.max(.25, Math.min(2, number)) * 20) / 20 : settingsDefaults.rotationSensitivity };
}
export const defaults = Object.freeze({ foil: .52, subjectScale: 1, subjectDepth: .32, backgroundDepth: -.18, effectsDepth: .14, finish: 'pearl' });
export const limits = Object.freeze({ foil: [0, 1], subjectScale: [.92, 1.35], subjectDepth: [-.45, .9], backgroundDepth: [-.4, .4], effectsDepth: [-.3, .9] });
const finishes = ['pearl', 'silver', 'gold', 'original'];
export const safeId = value => typeof value === 'string' && /^[a-zA-Z0-9_-]{1,100}$/.test(value) && !['__proto__', 'constructor', 'prototype'].includes(value);
export function cleanParameters(input = {}, base = defaults) {
  const clean = { ...base };
  for (const [key, [min, max]] of Object.entries(limits)) {
    const number = Number(input[key]);
    if (input[key] !== null && input[key] !== '' && Number.isFinite(number)) clean[key] = Math.max(min, Math.min(max, number));
  }
  if (finishes.includes(input.finish)) clean.finish = input.finish;
  return clean;
}
export function cleanState(input) {
  const state = { schema: 1, activeId: '', filter: 'all', sort: 'default', auto: false, fullscreen: false, settings: cleanSettings(), cards: {} };
  if (!input || typeof input !== 'object' || Array.isArray(input)) return state;
  state.settings = cleanSettings(input.settings);
  if (safeId(input.activeId)) state.activeId = input.activeId;
  if (['all', 'favorites'].includes(input.filter)) state.filter = input.filter;
  if (['default', 'name', 'newest'].includes(input.sort)) state.sort = input.sort;
  state.auto = input.auto === true;
  if (input.cards && typeof input.cards === 'object') for (const [id, card] of Object.entries(input.cards).slice(0, 5000)) {
    if (!safeId(id) || !card || typeof card !== 'object') continue;
    state.cards[id] = { favorite: card.favorite === true };
    if (typeof card.title === 'string' && card.title.trim()) state.cards[id].title = card.title.trim().slice(0, 60);
    if (card.parameters && typeof card.parameters === 'object') state.cards[id].parameters = cleanParameters(card.parameters);
  }
  return state;
}
export function readState() {
  try { return cleanState(JSON.parse(localStorage.getItem(STORE_KEY))); } catch { return cleanState(null); }
}
export function saveState(state) {
  try { localStorage.setItem(STORE_KEY, JSON.stringify({ ...state, settings: cleanSettings(state.settings), fullscreen: false })); return true; } catch { return false; }
}
export function mediaURL(value, base = document.baseURI, allowBlob = false) {
  if (typeof value !== 'string' || value.length > 2048) return '';
  try {
    const url = new URL(value, base);
    if (allowBlob && url.protocol === 'blob:' && url.origin === location.origin) return url.href;
    return url.origin === location.origin && ['http:', 'https:'].includes(url.protocol) ? url.href : '';
  } catch { return ''; }
}
export function cleanCard(card) {
  if (!card || !safeId(card.id)) return null;
  const cover = mediaURL(card.cover, document.baseURI, card.source === 'desktop');
  const configUrl = mediaURL(card.configUrl);
  const kind = card.kind === 'layered' ? 'layered' : 'image';
  if (!cover || (kind === 'layered' && !configUrl)) return null;
  const clean = { id: card.id, kind, cover, configUrl, source: ['imported', 'desktop'].includes(card.source) ? card.source : 'builtin' };
  for (const key of ['title', 'subtitle', 'description', 'edition']) clean[key] = typeof card[key] === 'string' ? card[key].slice(0, key === 'description' ? 1200 : 100) : '';
  clean.title ||= '未命名卡片';
  clean.createdAt = Number(card.createdAt) || 0;
  return clean;
}
let databasePromise;
function database() {
  databasePromise ||= new Promise((resolve, reject) => {
    const request = indexedDB.open('yingguang-images', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('images', { keyPath: 'id' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return databasePromise;
}
export async function desktopImages() {
  const db = await database();
  return new Promise((resolve, reject) => {
    const request = db.transaction('images').objectStore('images').getAll();
    request.onsuccess = () => resolve(request.result.map(item => ({ ...item, source: 'desktop', kind: 'image', cover: URL.createObjectURL(item.blob) })));
    request.onerror = () => reject(request.error);
  });
}
export async function putDesktopImage(file) {
  if (!/^image\/(png|jpeg|webp|gif)$/.test(file.type) || file.size > 30 * 1024 * 1024) throw new Error('请选择 30 MB 以内的 PNG、JPEG、WebP 或 GIF 图片。');
  const db = await database();
  const card = { id: `image_${crypto.randomUUID().replaceAll('-', '')}`, title: file.name.replace(/\.[^.]+$/, '').slice(0, 60), subtitle: '你的私人收藏', description: '由本机图片导入，保留原图。单图卡片支持光泽、比例与材质调节。', edition: 'PERSONAL IMAGE', blob: file, createdAt: Date.now() };
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('images', 'readwrite');
    transaction.objectStore('images').add(card);
    transaction.oncomplete = () => resolve(card);
    transaction.onerror = () => reject(transaction.error);
  });
}
export async function deleteDesktopImage(id) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('images', 'readwrite');
    transaction.objectStore('images').delete(id);
    transaction.oncomplete = () => resolve(true);
    transaction.onerror = () => reject(transaction.error);
  });
}
