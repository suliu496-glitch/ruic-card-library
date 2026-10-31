import { nativeScanDownloads, nativeRequestDownloadAccess, nativeImportDownloads, nativePreviewAvailable } from './native.js';

const byId = id => document.getElementById(id);
const maxSelection = 50;
const number = value => Number.isFinite(Number(value)) ? Math.max(0, Math.floor(Number(value))) : 0;
function payload(input) { return typeof input === 'string' ? JSON.parse(input) : input || {}; }
function sizeLabel(bytes) {
  const size = number(bytes);
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export function createDownloadsImport({ manualImport, previewFile, toast }) {
  const dialog = byId('downloads-dialog'), list = byId('downloads-list');
  let phase = 'idle', files = [], selected = new Set(), message = '', resultMessage = '', truncated = false;
  let scanned = false, scanTimer, previewTimer, progress = null, previewPending = false, advanceSelection = false;
  const busy = () => phase === 'importing';
  const available = () => files.filter(file => !file.imported);
  const selection = () => available().filter(file => selected.has(file.id));

  function renderSelection() {
    const selectable = available(), chosen = selection();
    const all = byId('downloads-all');
    all.checked = selectable.length > 0 && chosen.length === Math.min(maxSelection, selectable.length);
    all.indeterminate = chosen.length > 0 && !all.checked;
    all.disabled = busy() || previewPending || phase !== 'ready' || !selectable.length;
    byId('downloads-selection').textContent = `已选 ${chosen.length} / ${selectable.length} 个文件`;
    byId('downloads-import').disabled = busy() || previewPending || phase !== 'ready' || !chosen.length;
    byId('downloads-import').textContent = busy() ? '正在导入…' : `导入所选${chosen.length ? `（${chosen.length}）` : ''}`;
    byId('downloads-limit').hidden = selectable.length <= maxSelection;
    for (const row of list.children) {
      const checkbox = row.querySelector('input');
      checkbox.checked = selected.has(row.dataset.id) && !checkbox.dataset.imported;
      checkbox.disabled = busy() || previewPending || phase !== 'ready' || Boolean(checkbox.dataset.imported);
      const preview = row.querySelector('button');
      if (preview) preview.disabled = busy() || previewPending || phase !== 'ready';
    }
  }

  function renderFiles() {
    list.replaceChildren();
    for (const file of files) {
      const row = document.createElement('div'); row.className = 'download-file'; row.dataset.id = file.id;
      const choice = document.createElement('label'); choice.className = 'download-choice';
      const checkbox = document.createElement('input'); checkbox.type = 'checkbox';
      checkbox.setAttribute('aria-label', `选择 ${file.name}`);
      if (file.imported) { checkbox.dataset.imported = 'true'; row.classList.add('is-imported'); }
      checkbox.addEventListener('change', () => {
        if (checkbox.checked && selection().length >= maxSelection && !selected.has(file.id)) { checkbox.checked = false; renderSelection(); return; }
        checkbox.checked ? selected.add(file.id) : selected.delete(file.id); renderSelection();
      });
      const details = document.createElement('span'); details.className = 'download-file-details';
      const name = document.createElement('span'); name.className = 'download-file-name'; name.textContent = file.name;
      const meta = document.createElement('span'); meta.className = 'download-file-meta';
      meta.textContent = `${sizeLabel(file.size)} · ${file.cardCount ? `${file.cardCount} 张卡片` : '张数待确认'}`;
      details.append(name, meta); choice.append(checkbox, details); row.append(choice);
      if (file.imported) { const badge = document.createElement('span'); badge.className = 'download-imported'; badge.textContent = '已导入'; row.append(badge); }
      if (nativePreviewAvailable()) {
        const preview = document.createElement('button'); preview.className = 'download-preview'; preview.textContent = '预览';
        preview.setAttribute('aria-label', `预览 ${file.name}`);
        preview.onclick = () => {
          if (busy() || previewPending || phase !== 'ready') return;
          previewPending = true; render();
          clearTimeout(previewTimer);
          previewTimer = setTimeout(() => { previewPending = false; resultMessage = '预览暂未返回，请重试。也可以先手动选择文件。'; render(); }, 30000);
          try { previewFile(file.id); }
          catch (error) { clearTimeout(previewTimer); previewPending = false; resultMessage = `预览未能打开：${error.message || '请重试。'}`; render(); }
        };
        row.append(preview);
      }
      list.append(row);
    }
  }

  function render() {
    const scanning = phase === 'scanning', permission = phase === 'permission';
    let title = '', detail = message;
    if (scanning) { title = '正在扫描下载目录…'; detail ||= '正在查找 .ruic 闪卡与合集 ZIP。'; }
    else if (previewPending) { title = '正在打开临时预览…'; detail = '无需先导入。看过以后，可以再决定是否收入卡匣。'; }
    else if (permission) title = '允许扫描你的下载目录';
    else if (phase === 'error') title = '暂时无法扫描';
    else if (busy()) {
      title = progress ? `正在导入 ${progress.completed} / ${progress.total} 个文件` : '正在导入所选卡片…';
      detail = progress ? `已收入 ${progress.imported} 张 · 跳过 ${progress.skipped} · 失败 ${progress.failed}` : '可以关闭此窗口，卡片会继续收入本机卡匣。';
    } else if (phase === 'ready') {
      title = files.length ? `找到 ${files.length} 个卡片文件` : '下载目录里还没有卡片';
      detail ||= files.length ? '选择想收入卡匣的文件。已导入的文件无需重复选择。' : '下载 .ruic 闪卡或合集 ZIP 后，轻点刷新。也可以手动选择文件。';
    }
    byId('downloads-status-title').textContent = title;
    byId('downloads-message').textContent = detail;
    byId('downloads-status').dataset.state = phase;
    byId('downloads-status').setAttribute('aria-busy', String(scanning || busy() || previewPending));
    byId('downloads-access').hidden = !permission;
    byId('downloads-permission-note').hidden = !permission;
    byId('downloads-refresh').disabled = scanning || busy() || previewPending;
    byId('downloads-manual').disabled = busy() || previewPending;
    byId('downloads-results').hidden = !files.length || permission || phase === 'error';
    byId('downloads-truncated').hidden = !truncated || permission;
    byId('downloads-result').hidden = !resultMessage;
    byId('downloads-result').textContent = resultMessage;
    renderSelection();
  }

  function finishScanTimer() { clearTimeout(scanTimer); scanTimer = null; }
  function startScan(action = nativeScanDownloads) {
    if (busy() || previewPending || phase === 'scanning') return;
    phase = 'scanning'; message = ''; progress = null; render();
    finishScanTimer();
    scanTimer = setTimeout(() => { phase = 'error'; message = '扫描暂未返回，请刷新重试，或手动选择文件。'; render(); }, 30000);
    try { action(); }
    catch (error) { finishScanTimer(); phase = 'error'; message = error.message || '扫描未能开始，请重试。'; render(); }
  }

  byId('downloads-close').onclick = () => dialog.close();
  dialog.addEventListener('keydown', event => {
    if (event.key !== 'Tab') return;
    const focusable = [...dialog.querySelectorAll('button,input,[tabindex]')].filter(element => !element.disabled && element.tabIndex >= 0 && element.getClientRects().length);
    const first = focusable[0], last = focusable.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  });
  byId('downloads-refresh').onclick = () => startScan();
  byId('downloads-access').onclick = () => startScan(nativeRequestDownloadAccess);
  byId('downloads-manual').onclick = () => { if (!busy()) { dialog.close(); manualImport(); } };
  byId('downloads-all').onchange = event => {
    if (busy() || previewPending || phase !== 'ready') return;
    selected = new Set(event.target.checked ? available().slice(0, maxSelection).map(file => file.id) : []); renderSelection();
  };
  byId('downloads-import').onclick = () => {
    if (busy() || previewPending || phase !== 'ready') return;
    const ids = selection().map(file => file.id);
    if (!ids.length) return;
    if (ids.length > maxSelection) { selected = new Set(ids.slice(0, maxSelection)); renderSelection(); return; }
    phase = 'importing'; resultMessage = ''; progress = null; render();
    try { nativeImportDownloads(ids); }
    catch (error) { phase = 'ready'; resultMessage = `导入未能开始：${error.message || '请重试。'}`; render(); }
  };

  window.onNativeDownloadsResult = input => {
    if (busy()) return;
    finishScanTimer();
    try {
      const result = payload(input);
      message = typeof result.message === 'string' ? result.message : '';
      if (result.status === 'ready') {
        const ids = new Set();
        files = (Array.isArray(result.files) ? result.files : []).filter(file => file && typeof file.id === 'string' && file.id && !ids.has(file.id) && ids.add(file.id)).map(file => ({
          id: file.id, name: typeof file.name === 'string' ? file.name : '未命名卡片', size: number(file.size), cardCount: number(file.cardCount), imported: file.imported === true,
        }));
        const retained = available().filter(file => selected.has(file.id));
        const next = !scanned || (advanceSelection && !retained.length) ? available() : retained;
        selected = new Set(next.slice(0, maxSelection).map(file => file.id));
        advanceSelection = false;
        scanned = true; truncated = result.truncated === true; phase = 'ready'; renderFiles();
      } else { phase = result.status === 'permission_required' ? 'permission' : 'error'; message ||= '请刷新重试，或手动选择文件。'; }
    } catch { phase = 'error'; message = '扫描结果暂时无法读取，请刷新重试，或手动选择文件。'; }
    render();
  };
  window.onNativeDownloadImportProgress = input => {
    if (!busy()) return;
    try { const result = payload(input); progress = { completed: number(result.completed), total: number(result.total), imported: number(result.imported), skipped: number(result.skipped), failed: number(result.failed) }; render(); }
    catch { /* A progress update must never interrupt an import. */ }
  };
  window.onNativeDownloadImportResult = input => {
    if (!busy()) return;
    try {
      const result = payload(input), imported = number(result.imported), skipped = number(result.skipped), failed = number(result.failed);
      resultMessage = typeof result.message === 'string' && result.message ? result.message : `已收入 ${imported} 张卡片 · 跳过 ${skipped} · 失败 ${failed}`;
    } catch { resultMessage = '导入结果暂时无法读取，请刷新查看卡匣。'; }
    phase = 'ready'; progress = null; advanceSelection = true;
    if (!dialog.open) toast(resultMessage);
    startScan();
  };

  return {
    previewReady(message = '') { clearTimeout(previewTimer); previewPending = false; if (message) resultMessage = message; render(); },
    open() {
      if (!dialog.open) dialog.showModal();
      byId('downloads-close').focus({ preventScroll: true });
      if (busy() || previewPending || phase === 'scanning') render();
      else { resultMessage = ''; startScan(); }
    },
  };
}
