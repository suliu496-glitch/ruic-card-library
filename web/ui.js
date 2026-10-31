import { icon, refreshIcons } from './icons.js';
export const $ = id => document.getElementById(id);
let toastTimer;
export function toast(message) {
  clearTimeout(toastTimer);
  $('toast').textContent = message;
  $('toast').hidden = false;
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4000);
}
export function setRenderStatus(message) { $('render-status').textContent = message; }
export function renderCards(cards, allCount, activeId, state, onSelect, onFavorite) {
  const list = $('card-list');
  const position = { top: list.scrollTop, left: list.scrollLeft };
  const focusedRow = document.activeElement?.closest('.card-item');
  const focusId = focusedRow?.dataset.id;
  const focusClass = document.activeElement?.classList.contains('card-star') ? '.card-star' : '.card-select';
  $('total-count').textContent = allCount;
  $('shown-count').textContent = `${cards.length} 张卡片`;
  list.setAttribute('aria-busy', 'false');
  const rows = new Map([...list.querySelectorAll('.card-item')].map(row => [row.dataset.id, row]));
  const desired = [];
  cards.forEach((card, index) => {
    let row = rows.get(card.id);
    if (!row) {
      row = document.createElement('article');
      row.dataset.id = card.id;
      const button = document.createElement('button'); button.className = 'card-select';
      const img = document.createElement('img'); img.className = 'thumbnail'; img.alt = ''; img.loading = 'lazy'; img.draggable = false;
      const summary = document.createElement('span'); summary.className = 'card-summary';
      const number = document.createElement('span'); number.className = 'card-number';
      summary.append(number, document.createElement('strong'), document.createElement('small'));
      button.append(img, summary);
      const favorite = document.createElement('button'); favorite.className = 'card-star'; favorite.append(icon('heart'));
      row.append(button, favorite);
    }
    row.className = `card-item${card.id === activeId ? ' active' : ''}`;
    const button = row.querySelector('.card-select');
    button.setAttribute('aria-label', `打开${card.title}`);
    button.setAttribute('aria-current', card.id === activeId ? 'true' : 'false');
    button.onclick = () => onSelect(card.id);
    const img = row.querySelector('img');
    if (img.getAttribute('src') !== card.cover) img.src = card.cover;
    row.querySelector('.card-number').textContent = String(index + 1).padStart(2, '0');
    row.querySelector('strong').textContent = card.title;
    row.querySelector('small').textContent = card.subtitle || (card.kind === 'layered' ? '分层全息闪卡' : '单图光泽');
    const favorite = row.querySelector('.card-star');
    const isFavorite = !!state.cards[card.id]?.favorite;
    favorite.setAttribute('aria-label', `${isFavorite ? '取消收藏' : '收藏'}${card.title}`);
    favorite.setAttribute('aria-pressed', String(isFavorite));
    favorite.onclick = () => onFavorite(card.id);
    desired.push(row);
  });
  if (!cards.length) {
    const empty = document.createElement('p');
    empty.className = 'list-placeholder';
    empty.textContent = $('search').value ? '没有找到这张卡片。试试其他名称。' : state.filter === 'favorites' ? '还没有收藏。轻点卡片上的爱心，把喜欢的画面留在这里。' : '卡匣还空着。导入喜欢的图片或闪卡 ZIP，开始你的收藏。';
    desired.push(empty);
  }
  // Keep unchanged card/image nodes mounted: selection must not rebuild the album
  // or lose the scroll anchor and focused tile in an Android WebView.
  let cursor = list.firstChild;
  for (const node of desired) {
    if (node === cursor) cursor = cursor.nextSibling;
    else list.insertBefore(node, cursor);
  }
  const keep = new Set(desired);
  [...list.childNodes].forEach(node => { if (!keep.has(node)) node.remove(); });
  if (focusId && !focusedRow?.isConnected) list.querySelector(`[data-id="${CSS.escape(focusId)}"] ${focusClass}`)?.focus({ preventScroll: true });
  list.scrollTop = position.top; list.scrollLeft = position.left;
  $('filter-all').setAttribute('aria-pressed', String(state.filter === 'all'));
  $('filter-favorites').setAttribute('aria-pressed', String(state.filter === 'favorites'));
  $('sort').value = state.sort;
}
export function renderActive(card, state, index, count) {
  const exists = !!card;
  for (const id of ['rename', 'favorite', 'auto', 'flip', 'reset']) $(id).disabled = !exists;
  $('layers-toggle').disabled = !exists || card.kind !== 'layered';
  $('layers-toggle').title = exists && card.kind === 'image' ? '单图卡片没有独立图层' : '拆开真实图层，旋转查看';
  $('parameters').disabled = !exists;
  $('previous').disabled = count < 2; $('next').disabled = count < 2;
  $('position').textContent = `${String(index + 1).padStart(2, '0')} / ${String(count).padStart(2, '0')}`;
  if (!exists) {
    $('active-title').textContent = '映光卡匣';
    $('active-subtitle').textContent = '为你的画面留一束光。';
    $('active-description').textContent = '收藏、观赏与微调，都留在你的设备里。';
    $('active-edition').textContent = 'PERSONAL COLLECTION';
    $('card-kind').textContent = '私人典藏';
    $('layer-note').textContent = '导入图片或闪卡 ZIP，开始你的收藏。';
    $('delete').hidden = true;
    return;
  }
  $('active-title').textContent = card.title;
  $('active-subtitle').textContent = card.subtitle;
  $('active-description').textContent = card.description;
  $('active-edition').textContent = card.edition || 'PERSONAL COLLECTION';
  $('card-kind').textContent = card.kind === 'layered' ? '分层全息' : '单图光泽';
  $('layer-note').textContent = card.kind === 'layered' ? '人物、背景与特效分别成层。拖动卡片，让光泽与景深随视角变化。' : '这是一张单图收藏，支持光泽、比例与材质微调。景深调节仅适用于分层闪卡。';
  $('favorite').setAttribute('aria-pressed', String(!!state.cards[card.id]?.favorite));
  $('favorite').setAttribute('aria-label', state.cards[card.id]?.favorite ? '取消收藏当前卡片' : '收藏当前卡片');
  $('delete').hidden = card.source === 'builtin';
}
export function renderParameters(parameters, card, hasEffects = true, ranges) {
  document.querySelectorAll('[data-parameter]').forEach(input => {
    const key = input.dataset.parameter;
    if (ranges?.[key]) { input.min = ranges[key][0]; input.max = ranges[key][1]; }
    input.value = parameters[key];
    input.disabled = card?.kind === 'image' && ['subjectDepth', 'backgroundDepth', 'effectsDepth'].includes(key);
    if (key === 'effectsDepth' && !hasEffects) input.disabled = true;
    if (key === 'foil' && parameters.finish === 'original') input.disabled = true;
    const number = Number(input.value);
    const percentage = 100 * (number - Number(input.min)) / (Number(input.max) - Number(input.min));
    input.style.setProperty('--range', `${percentage}%`);
    $(`${key}-value`).value = key === 'foil' ? `${Math.round(number * 100)}%` : number.toFixed(2);
  });
  document.querySelectorAll('[data-finish]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.finish === parameters.finish)));
}
export function renderViewState(auto, flipped) {
  $('auto').setAttribute('aria-pressed', String(auto));
  $('auto-label').textContent = auto ? '暂停转动' : '自动转动';
  $('auto').querySelector('[data-icon]').dataset.icon = auto ? 'pause' : 'play';
  refreshIcons($('auto'));
  $('flip').setAttribute('aria-pressed', String(flipped));
  $('flip-label').textContent = flipped ? '翻至正面' : '翻至背面';
}
export function renderLayerState(layered, gap = 1) {
  $('layers-toggle').setAttribute('aria-pressed', String(layered));
  $('layers-label').textContent = layered ? '合回卡片' : '分层展示';
  $('layer-controls').hidden = !layered;
  $('layer-gap').value = gap;
  $('layer-gap-value').value = Number(gap).toFixed(2);
  $('layer-gap').style.setProperty('--range', `${100 * (gap - .25) / 1.75}%`);
}
export function showViewerError(message, retry) {
  setRenderStatus('加载遇到问题');
  toast(message);
  const display = $('display');
  if (display.querySelector('iframe')) return;
  const block = document.createElement('div'); block.className = 'stage-error';
  const text = document.createElement('p'); text.textContent = message;
  const button = document.createElement('button'); button.textContent = '重新加载'; button.onclick = retry;
  block.append(text, button); display.replaceChildren(block);
}
