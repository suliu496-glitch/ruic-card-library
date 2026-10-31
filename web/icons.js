const paths = {
  cards: '<rect x="7" y="4" width="13" height="17" rx="2"/><path d="M16 4V2H4v16h3m3-7 2 2 4-4"/>',
  heart: '<path d="M20.5 5.8a5.2 5.2 0 0 0-7.4 0L12 7l-1.1-1.2a5.2 5.2 0 0 0-7.4 7.4L12 21l8.5-7.8a5.2 5.2 0 0 0 0-7.4Z"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  dense: '<path d="M3 3h4v4H3zM10 3h4v4h-4zM17 3h4v4h-4zM3 10h4v4H3zM10 10h4v4h-4zM17 10h4v4h-4zM3 17h4v4H3zM10 17h4v4h-4zM17 17h4v4h-4z"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  settings: '<path d="m9.4 3-.6 2.3-2 .9-2.1-.7-1.8 3 1.6 1.7v2.4l-1.6 1.7 1.8 3 2.1-.7 2 .9.6 2.5h3.4l.6-2.5 2-.9 2.1.7 1.8-3-1.6-1.7v-2.4l1.6-1.7-1.8-3-2.1.7-2-.9-.6-2.3H9.4Z"/><circle cx="11.1" cy="11.4" r="3"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  search: '<circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.4 4.4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
  edit: '<path d="m15 4 5 5M4 20l5-1L21 7a2.8 2.8 0 0 0-4-4L5 15l-1 5Z"/>',
  star: '<path d="m12 3 2.8 5.7 6.3.9-4.5 4.4 1.1 6.2-5.7-3-5.7 3 1.1-6.2L3.2 9.6l6.3-.9L12 3Z"/>',
  left: '<path d="m14.5 6-6 6 6 6"/>',
  right: '<path d="m9.5 6 6 6-6 6"/>',
  play: '<path d="m8 5 11 7-11 7V5Z"/>',
  pause: '<path d="M8 5v14M16 5v14"/>',
  flip: '<path d="M4 8h13l-3-3M20 16H7l3 3M4 8v7M20 16V9"/>',
  maximize: '<path d="M8 3H3v5M16 3h5v5M21 16v5h-5M3 16v5h5"/>',
  minimize: '<path d="M3 8h5V3M21 8h-5V3M16 21v-5h5M8 21v-5H3"/>',
  reset: '<path d="M3 11a9 9 0 1 1 2.6 7M3 4v7h7"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"/>',
  layers: '<path d="m12 3 9 5-9 5-9-5 9-5ZM3 12l9 5 9-5M3 16l9 5 9-5"/>',
};
export function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  for (const [key, value] of Object.entries({ viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.5', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' })) svg.setAttribute(key, value);
  svg.innerHTML = paths[name] || paths.star;
  return svg;
}
export function refreshIcons(root = document) {
  root.querySelectorAll('[data-icon]').forEach(element => element.replaceChildren(icon(element.dataset.icon)));
}
