import { refreshIcons } from './icons.js';
import { readState, saveState, cleanState, cleanSettings, cleanParameters, cleanCard, defaults, limits, desktopImages, putDesktopImage, deleteDesktopImage } from './store.js';
import { nativeAvailable, nativeCards, nativeImport, nativeDownloadsAvailable, nativePreviewDownload, nativeCommitPreview, nativeClosePreview, nativeBackup, nativeDelete, nativeFullscreen, downloadState } from './native.js';
import { ViewerController } from './viewer-controller.js';
import { $, toast, setRenderStatus, renderCards, renderActive, renderParameters, renderViewState, renderLayerState, showViewerError } from './ui.js';
import { initSettings, renderSettings } from './settings.js';
import { createPreviewUI } from './preview-ui.js';
import { createDownloadsImport } from './downloads-import.js';

let state = readState(), cards = [], builtin = [], active = null, filtered = [], flipped = false, query = '';
let cardDefaults = { ...defaults }, cardRanges = limits, hasEffects = true, loading = false, catalogError = '';
let layered = false, layerGap = 1;
let screen = 'library';
let libraryPosition = { top: 0, left: 0, pageTop: 0 };
let saveFailed = false;
let fullscreenRevision = 0;
let cancelLibraryRestore;
let previewSession = null, committingPreview = null;
const previewUI = createPreviewUI(positionLayerControls);
const downloadsImport = createDownloadsImport({ manualImport: importFilesManually, previewFile: nativePreviewDownload, toast });
const viewer = new ViewerController($('display'), {
  ready(data) {
    cardDefaults = cleanParameters(data.defaults);
    cardRanges = data.ranges || limits;
    hasEffects = data.hasEffects !== false;
    if (!active) return;
    const saved = entry(active.id).parameters;
    entry(active.id).parameters = cleanParameters(saved || cardDefaults, cardDefaults);
    viewer.setParameters(entry(active.id).parameters);
    renderParameters(entry(active.id).parameters, active, hasEffects, cardRanges);
    setRenderStatus(data.mode === 'image' ? '单图光泽' : data.mode === 'fallback' ? '轻量 3D 模式' : 'RuiC · 实时分层');
    persist();
  },
  state(viewState) {
    if (!active) return;
    currentState().auto = viewState.auto;
    flipped = viewState.flipped;
    if (typeof viewState.layered === 'boolean') layered = viewState.layered;
    if (Number.isFinite(viewState.layerGap)) layerGap = viewState.layerGap;
    renderViewState(currentState().auto, flipped);
    renderLayerState(layered, layerGap);
    persist();
  },
  error(message) { showViewerError(message, () => selectCard(active?.id, true)); },
  back() { handleBack(); },
});

// Preview adjustments live only in this session; persist() always saves the library state.
function currentState() { return previewSession?.state || state; }
function entry(id) { return currentState().cards[id] ||= { favorite: false }; }
function persist() {
  const success = saveState(state);
  $('save-status').textContent = success ? previewSession ? '临时调校 · 未写入卡库' : '已保存到本机' : '存储空间不足';
  $('settings-save-error').hidden = success;
  if (!success) {
    $('save-status').title = '请先备份卡匣，清理设备存储后再试。';
    if (!saveFailed) toast('本机存储失败，刚才的设置尚未保存。请先备份卡匣，再清理设备存储。');
  } else $('save-status').removeAttribute('title');
  saveFailed = !success;
}
function visibleCards() {
  const result = cards.map(card => ({ ...card, title: state.cards[card.id]?.title || card.title })).filter(card => {
    if (state.filter === 'favorites' && !state.cards[card.id]?.favorite) return false;
    return !query || `${card.title} ${card.subtitle} ${card.description}`.toLocaleLowerCase().includes(query);
  });
  if (state.sort === 'name') result.sort((a, b) => a.title.localeCompare(b.title, 'zh-CN'));
  if (state.sort === 'newest') result.sort((a, b) => b.createdAt - a.createdAt);
  return result;
}
function renderLibrary() {
  const current = currentState();
  filtered = previewSession ? previewSession.cards : visibleCards();
  if (!previewSession) renderCards(filtered, cards.length, state.activeId, state, selectCard, toggleFavorite);
  const index = filtered.findIndex(card => card.id === current.activeId);
  renderActive(active, current, index, filtered.length);
  $('commit-preview').hidden = !previewSession;
  $('commit-preview').disabled = Boolean(previewSession?.committing || previewSession?.committed || committingPreview);
  $('commit-preview').textContent = previewSession?.committing ? '正在收入…' : previewSession?.committed ? '已收入卡库' : '收入卡库';
  document.body.classList.toggle('temporary-preview', Boolean(previewSession));
  if (previewSession) { $('delete').hidden = true; $('rename').disabled = true; $('favorite').disabled = true; $('card-kind').textContent = '临时预览'; }
  if (active && index < 0) $('position').textContent = `— / ${String(filtered.length).padStart(2, '0')}`;
}
function setScreen(value) {
  screen = value;
  document.querySelector('.workspace').dataset.view = value;
  for (const selector of ['.library', '.gallery']) {
    const element = document.querySelector(selector);
    const hidden = selector === '.library' ? value !== 'library' : value !== 'preview';
    element.inert = hidden;
    element.setAttribute('aria-hidden', String(hidden));
  }
  document.querySelector('.app-header').inert = value === 'preview';
  previewUI.setVisible(value === 'preview');
}
function showLibrary() {
  if (screen !== 'preview') return;
  cancelLibraryRestore?.();
  const controller = new AbortController();
  const restore = () => {
    if (screen !== 'library' || controller.signal.aborted) return;
    $('card-list').scrollTop = libraryPosition.top;
    $('card-list').scrollLeft = libraryPosition.left;
    window.scrollTo(0, libraryPosition.pageTop);
  };
  // Android restores system-bar insets asynchronously. A temporary taller
  // album can clamp scrollTop before the final viewport resize arrives.
  const timer = setTimeout(() => controller.abort(), 1000);
  cancelLibraryRestore = () => { clearTimeout(timer); controller.abort(); cancelLibraryRestore = null; };
  window.addEventListener('resize', restore, { signal: controller.signal });
  for (const type of ['pointerdown', 'wheel', 'keydown']) window.addEventListener(type, cancelLibraryRestore, { capture: true, once: true, signal: controller.signal });
  fullscreen(false);
  viewer.close();
  if (previewSession) {
    const sessionId = previewSession.id;
    previewSession = null;
    const found = cards.find(card => card.id === state.activeId) || cards[0];
    state.activeId = found?.id || '';
    active = found ? { ...found, title: state.cards[found.id]?.title || found.title } : null;
    renderLibrary();
    try { nativeClosePreview(sessionId); } catch (error) { console.warn('Preview cleanup failed:', error); }
  }
  setScreen('library');
  restore(); requestAnimationFrame(restore);
  $('card-list').querySelector(`[data-id="${CSS.escape(state.activeId)}"] .card-select`)?.focus({ preventScroll: true });
}
function selectCard(id, force = false) {
  const current = currentState();
  const found = (previewSession?.cards || cards).find(card => card.id === id);
  const entering = screen !== 'preview';
  if (!found || (!entering && active?.id === id && !force)) return;
  cancelLibraryRestore?.();
  if (entering) libraryPosition = { top: $('card-list').scrollTop, left: $('card-list').scrollLeft, pageTop: window.scrollY };
  setScreen('preview');
  // Request immersive display in the card click's activation; the CSS preview
  // remains edge-to-edge when browser fullscreen is unavailable or dismissed.
  if (entering) fullscreen(true);
  if (entering) { window.scrollTo(0, 0); $('back-library').focus({ preventScroll: true }); }
  active = { ...found, title: current.cards[id]?.title || found.title };
  current.activeId = id;
  flipped = false;
  layered = false; layerGap = 1; renderLayerState(false, layerGap);
  cardDefaults = { ...defaults }; cardRanges = limits; hasEffects = true;
  renderLibrary();
  renderParameters(entry(id).parameters || defaults, active);
  renderViewState(current.auto, flipped);
  setRenderStatus('正在布置光影…');
  viewer.open(active, entry(id).parameters, current.auto);
  persist();
}
function toggleFavorite(id) {
  if (previewSession) return;
  entry(id).favorite = !entry(id).favorite;
  renderLibrary(); persist();
}
function changeParameter(key, value) {
  if (!active) return;
  entry(active.id).parameters = cleanParameters({ ...(entry(active.id).parameters || cardDefaults), [key]: value });
  viewer.setParameters(entry(active.id).parameters);
  renderParameters(entry(active.id).parameters, active, hasEffects, cardRanges);
  persist();
}
function stepCard(direction) {
  if (!filtered.length) return;
  const index = filtered.findIndex(card => card.id === currentState().activeId);
  const next = index < 0 ? 0 : (index + direction + filtered.length) % filtered.length;
  selectCard(filtered[next].id);
}
async function loadCards() {
  if (loading) return;
  loading = true;
  try {
    let imported = [];
    if (nativeAvailable()) imported = nativeCards();
    else {
      cards.filter(card => card.source === 'desktop').forEach(card => URL.revokeObjectURL(card.cover));
      try { imported = (await desktopImages()).map(cleanCard).filter(Boolean); }
      catch (error) { console.warn('Image storage unavailable:', error); }
    }
    const unique = new Map();
    [...builtin, ...imported].forEach(card => unique.set(card.id, card));
    cards = [...unique.values()];
    // Native imports may finish while a temporary collection is still being viewed.
    if (previewSession) return;
    const currentExists = cards.some(card => card.id === state.activeId);
    if (!currentExists) { active = null; viewer.close(); state.activeId = ''; }
    const selected = cards.find(card => card.id === state.activeId) || cards[0];
    if (selected) {
      state.activeId = selected.id;
      active = { ...selected, title: state.cards[selected.id]?.title || selected.title };
    }
    renderLibrary();
    if (cards.length && screen === 'preview') selectCard(state.activeId, true);
    else if (!cards.length) {
      showLibrary();
      setRenderStatus(catalogError ? '内置卡片暂未就绪' : '等待你的第一张卡片');
      const empty = document.createElement('div'); empty.className = 'stage-empty';
      const text = document.createElement('p'); text.textContent = catalogError || '把喜欢的画面，收入卡匣。';
      empty.append(text); $('display').replaceChildren(empty);
    }
  } catch (error) { toast(`卡匣未能打开：${error.message}`); }
  finally { loading = false; $('card-list').setAttribute('aria-busy', 'false'); }
}
async function initCatalog() {
  try {
    const url = new URL('./cards/catalog.json', document.baseURI);
    const response = await fetch(url);
    if (!response.ok) throw new Error('没有找到内置卡片目录。');
    const list = await response.json();
    if (!Array.isArray(list)) throw new Error('内置卡片目录格式不正确。');
    builtin = list.map(card => cleanCard({ ...card, source: 'builtin', cover: new URL(card.cover || '', url).href, configUrl: card.configUrl ? new URL(card.configUrl, url).href : '' })).filter(Boolean);
    if (!builtin.length) throw new Error('内置卡片正在准备中，你也可以先导入图片。');
  } catch (error) { catalogError = error.message; }
  await loadCards();
}
async function importFiles() {
  if (nativeDownloadsAvailable()) { downloadsImport.open(); return; }
  importFilesManually();
}
function importFilesManually() {
  try { if (nativeAvailable()) nativeImport(); else $('file-input').click(); }
  catch (error) { toast(`导入未能开始：${error.message}`); }
}
async function importDesktop(files) {
  let imported = 0, lastId;
  for (const file of files) {
    try { const card = await putDesktopImage(file); imported++; lastId = card.id; }
    catch (error) { toast(`${file.name}：${error.message}`); }
  }
  if (imported) { state.filter = 'all'; query = ''; $('search').value = ''; state.activeId = lastId; await loadCards(); toast(`已收入 ${imported} 张图片 · 单图光泽`); }
  $('file-input').value = '';
}
function fullscreen(value) {
  const revision = ++fullscreenRevision;
  state.fullscreen = !!value && screen === 'preview';
  document.body.classList.toggle('cinema', state.fullscreen);
  positionLayerControls();
  $('fullscreen').setAttribute('aria-pressed', String(state.fullscreen));
  $('fullscreen').setAttribute('aria-label', state.fullscreen ? '退出系统全屏' : '进入系统全屏');
  $('fullscreen').title = state.fullscreen ? '退出系统全屏' : '进入系统全屏';
  try { nativeFullscreen(state.fullscreen); } catch (error) { console.warn(error); }
  if (!nativeAvailable()) {
    if (state.fullscreen && !document.fullscreenElement) document.documentElement.requestFullscreen?.().then(() => {
      // A request may resolve after the user has already returned to the album.
      if (revision !== fullscreenRevision && !state.fullscreen && document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
    }).catch(() => {});
    if (!state.fullscreen && document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
  }
}
function positionLayerControls() {
  const controls = $('layer-controls');
  const compact = screen === 'preview' && !previewUI.isOpen();
  controls.classList.toggle('compact', compact);
  if (compact) document.querySelector('.gallery').insertBefore(controls, $('display'));
  else document.querySelector('.tuning').insertBefore(controls, $('parameters'));
}
function handleBack() {
  const dialog = document.querySelector('dialog[open]');
  if (dialog) { dialog.close(); return true; }
  if (screen !== 'preview') return false;
  if (previewUI.isOpen()) { previewUI.setOpen(false); return true; }
  showLibrary();
  return true;
}
function restoreState(input) {
  try {
    const restored = cleanState(typeof input === 'string' ? JSON.parse(input) : input);
    if (previewSession) showLibrary();
    if (state.fullscreen) fullscreen(false);
    viewer.close();
    state = restored; query = ''; $('search').value = '';
    renderSettings(state.settings);
    viewer.setRotationSensitivity(state.settings.rotationSensitivity);
    persist(); loadCards(); toast('卡匣参数与收藏已恢复。');
  } catch { toast('备份参数格式不正确，未能恢复。'); }
}

function openNativePreview(input) {
  downloadsImport.previewReady();
  let result;
  try { result = typeof input === 'string' ? JSON.parse(input) : input; }
  catch { downloadsImport.previewReady('临时预览结果无法读取，请重试。'); toast('临时预览结果无法读取，请重试。'); return; }
  const sessionId = typeof result?.sessionId === 'string' ? result.sessionId : '';
  const temporaryCards = (Array.isArray(result?.cards) ? result.cards : []).map(cleanCard).filter(Boolean).map(card => ({ ...card, source: 'preview' }));
  if (!result?.ok || !sessionId || !temporaryCards.length) {
    const message = typeof result?.message === 'string' ? result.message : '这份文件没有可预览的卡片。';
    downloadsImport.previewReady(message); toast(message);
    if (sessionId) { try { nativeClosePreview(sessionId); } catch (error) { console.warn(error); } }
    return;
  }
  for (const dialog of document.querySelectorAll('dialog[open]')) dialog.close();
  $('toast').hidden = true;
  viewer.close();
  if (previewSession && previewSession.id !== sessionId) { try { nativeClosePreview(previewSession.id); } catch (error) { console.warn(error); } }
  previewSession = { id: sessionId, cards: temporaryCards, state: { ...cleanState(state), activeId: '', auto: false, cards: {} }, committing: false, committed: false };
  previewUI.setOpen(false, { focus: false });
  selectCard(temporaryCards[0].id, true);
  fullscreen(true);
  $('back-library').focus({ preventScroll: true });
}

refreshIcons();
setScreen('library');
initSettings(settings => {
  state.settings = cleanSettings(settings);
  renderSettings(state.settings);
  viewer.setRotationSensitivity(state.settings.rotationSensitivity);
  persist();
});
renderSettings(state.settings);
viewer.setRotationSensitivity(state.settings.rotationSensitivity);
$('back-library').onclick = showLibrary;
positionLayerControls();
window.addEventListener('resize', positionLayerControls);
$('search').addEventListener('input', event => { query = event.target.value.trim().toLocaleLowerCase(); renderLibrary(); });
for (const filter of ['all', 'favorites']) $(`filter-${filter}`).onclick = () => { state.filter = filter; renderLibrary(); persist(); };
$('sort').onchange = event => { state.sort = event.target.value; renderLibrary(); persist(); };
$('favorite').onclick = () => active && toggleFavorite(active.id);
$('previous').onclick = () => stepCard(-1); $('next').onclick = () => stepCard(1);
$('auto').onclick = () => { const current = currentState(); current.auto = !current.auto; if (current.auto && flipped) { flipped = false; viewer.flip(false); } viewer.setAuto(current.auto); renderViewState(current.auto, flipped); persist(); };
$('flip').onclick = () => { flipped = !flipped; currentState().auto = false; viewer.flip(flipped); renderViewState(currentState().auto, flipped); persist(); };
document.querySelectorAll('[data-parameter]').forEach(input => input.addEventListener('input', () => changeParameter(input.dataset.parameter, Number(input.value))));
document.querySelectorAll('[data-finish]').forEach(button => { button.onclick = () => changeParameter('finish', button.dataset.finish); });
$('reset').onclick = () => { if (!active) return; entry(active.id).parameters = { ...cardDefaults }; currentState().auto = false; flipped = false; layered = false; layerGap = 1; viewer.setLayers(false); viewer.setLayerGap(1); renderLayerState(false, 1); viewer.reset(cardDefaults); renderParameters(cardDefaults, active, hasEffects, cardRanges); renderViewState(false, false); persist(); toast('已恢复这张卡的默认光影。'); };
$('layers-toggle').onclick = () => { if (active?.kind !== 'layered') return; layered = !layered; viewer.setLayers(layered); renderLayerState(layered, layerGap); };
$('layer-gap').oninput = event => { layerGap = Number(event.target.value); viewer.setLayerGap(layerGap); renderLayerState(layered, layerGap); };
$('rename').onclick = () => { if (!active) return; $('name-input').value = active.title; $('rename-dialog').showModal(); $('name-input').focus(); $('name-input').select(); };
$('rename-form').onsubmit = event => { event.preventDefault(); const title = $('name-input').value.trim(); if (!title || !active) return; entry(active.id).title = title.slice(0, 60); active.title = entry(active.id).title; renderLibrary(); persist(); $('rename-dialog').close(); toast('名称已保存。'); };
document.querySelectorAll('[data-close]').forEach(button => { button.onclick = () => $(button.dataset.close).close(); });
$('delete').onclick = () => { if (!active || active.source === 'builtin') return; $('delete-message').textContent = `「${active.title}」与它的调校参数会从卡匣移除。原始导入文件不受影响。`; $('delete-dialog').showModal(); };
$('confirm-delete').onclick = async () => {
  if (!active || active.source === 'builtin') return;
  const id = active.id;
  try {
    const success = active.source === 'desktop' ? await deleteDesktopImage(id) : nativeDelete(id);
    if (!success) throw new Error('卡片未能删除，请重试。');
    delete state.cards[id]; active = null; state.activeId = ''; viewer.close(); persist();
    $('delete-dialog').close(); await loadCards(); toast('这张卡片已移出卡匣。');
  } catch (error) { toast(error.message); }
};
$('import').onclick = importFiles;
$('commit-preview').onclick = () => {
  if (!previewSession || previewSession.committing || previewSession.committed || committingPreview) return;
  previewSession.committing = true; committingPreview = previewSession.id; renderLibrary();
  try { nativeCommitPreview(previewSession.id); }
  catch (error) { previewSession.committing = false; committingPreview = null; renderLibrary(); toast(`收入卡库未能开始：${error.message || '请重试。'}`); }
};
$('file-input').onchange = event => importDesktop([...event.target.files]);
$('backup').onclick = () => { try { if (nativeAvailable()) nativeBackup(state); else { downloadState(state); toast('已导出参数备份。完整图片与闪卡 ZIP 备份可在安卓应用中使用。'); } } catch (error) { toast(`备份未能开始：${error.message}`); } };
$('fullscreen').onclick = () => fullscreen(!state.fullscreen);
$('exit-fullscreen').onclick = showLibrary;
$('exit-fullscreen').setAttribute('aria-label', '关闭预览，返回卡库');
$('exit-fullscreen').title = '返回卡库';
document.addEventListener('fullscreenchange', () => {
  if (nativeAvailable()) return;
  const entered = !!document.fullscreenElement && screen === 'preview';
  // Synchronize state only: calling fullscreen() here would request it again.
  state.fullscreen = entered;
  document.body.classList.toggle('cinema', entered);
  $('fullscreen').setAttribute('aria-pressed', String(entered));
  $('fullscreen').setAttribute('aria-label', entered ? '退出系统全屏' : '进入系统全屏');
  $('fullscreen').title = entered ? '退出系统全屏' : '进入系统全屏';
  if (document.fullscreenElement && screen !== 'preview') document.exitFullscreen?.().catch(() => {});
  positionLayerControls();
});
window.addEventListener('keydown', event => {
  if (event.key === 'Escape' && !document.querySelector('dialog[open]')) {
    if (handleBack()) event.preventDefault();
    return;
  }
  if (event.target.matches('input,select,textarea') || document.querySelector('dialog[open]')) return;
  if (screen === 'preview' && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) { event.preventDefault(); stepCard(event.key === 'ArrowRight' ? 1 : -1); }
});
window.onNativeCardsChanged = () => loadCards();
window.onNativePreviewCards = openNativePreview;
window.onNativePreviewCommitted = input => {
  let result;
  try { result = typeof input === 'string' ? JSON.parse(input) : input || {}; }
  catch { result = { message: '收入结果暂时无法读取，请返回卡库确认。' }; }
  const sessionId = typeof result.sessionId === 'string' && result.sessionId ? result.sessionId : committingPreview;
  if (committingPreview && sessionId && sessionId !== committingPreview) return;
  committingPreview = null;
  if (previewSession) {
    if (previewSession.id === sessionId) { previewSession.committing = false; previewSession.committed = result.ok === true && !(Number(result.failed) > 0); }
    renderLibrary();
  }
  toast(typeof result.message === 'string' ? result.message : result.ok ? `已收入 ${Number(result.imported) || 0} 张卡片。` : '未能收入卡库，请重试。');
  loadCards();
};
window.onNativeRestoreState = restoreState;
window.onNativeResult = result => { if (result && typeof result.message === 'string') toast(result.message.slice(0, 300)); if (result?.state) restoreState(result.state); };
window.handleNativeBack = handleBack;
window.__cardLibrary = { getState: () => structuredClone(state), getCards: () => cards.map(card => ({ ...card })), getView: () => screen, getPreview: () => ({ parametersOpen: previewUI.isOpen(), fullscreen: state.fullscreen }), select: selectCard, back: showLibrary, viewer, restoreState };
initCatalog();
