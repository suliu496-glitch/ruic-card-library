import { cleanCard } from './store.js';
export function nativeAvailable() { return !!window.NativeCards && typeof window.NativeCards.listCards === 'function'; }
export function nativeCards() {
  if (!nativeAvailable()) return [];
  const parsed = JSON.parse(window.NativeCards.listCards());
  const cards = Array.isArray(parsed) ? parsed : parsed?.cards;
  if (!Array.isArray(cards)) throw new Error('本机卡匣列表格式不正确。');
  return cards.map(card => cleanCard({ ...card, source: 'imported' })).filter(Boolean);
}
export function nativeImport() { window.NativeCards.importFiles(); }
export function nativeDownloadsAvailable() { return nativeAvailable() && ['scanDownloads', 'requestDownloadAccess', 'importDownloads'].every(method => typeof window.NativeCards[method] === 'function'); }
export function nativeScanDownloads() { window.NativeCards.scanDownloads(); }
export function nativeRequestDownloadAccess() { window.NativeCards.requestDownloadAccess(); }
export function nativeImportDownloads(ids) { window.NativeCards.importDownloads(JSON.stringify(ids)); }
export function nativePreviewAvailable() { return typeof window.NativeCards?.previewDownload === 'function'; }
export function nativePreviewDownload(id) { window.NativeCards.previewDownload(id); }
export function nativeCommitPreview(sessionId) { window.NativeCards.commitPreview(sessionId); }
export function nativeClosePreview(sessionId) { if (typeof window.NativeCards?.closePreview === 'function') window.NativeCards.closePreview(sessionId); }
export function nativeBackup(state) { window.NativeCards.exportLibrary(JSON.stringify({ ...state, fullscreen: false })); }
export function nativeDelete(id) { return window.NativeCards.deleteCard(id) === true; }
export function nativeFullscreen(value) { if (typeof window.NativeCards?.setFullscreen === 'function') window.NativeCards.setFullscreen(value); }
export function downloadState(state) {
  const blob = new Blob([JSON.stringify({ ...state, fullscreen: false }, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const date = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  const link = document.createElement('a'); link.href = url; link.download = `映光卡匣-参数-${date}.json`; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}
