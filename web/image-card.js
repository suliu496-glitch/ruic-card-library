// A single imported image has a foil overlay, never invented depth layers.
export function normalizeRotationSensitivity(value) {
  return Number.isFinite(value) ? Math.round(Math.max(.25, Math.min(2, value)) * 20) / 20 : 1;
}
export function createImageCard(container, card, onInteraction) {
  const stage = document.createElement('div');
  stage.className = 'image-stage';
  stage.tabIndex = 0;
  stage.setAttribute('role', 'group');
  stage.setAttribute('aria-label', `${card.title}，可拖动的单图光泽卡片`);
  const surface = document.createElement('div');
  surface.className = 'single-card';
  const front = document.createElement('div');
  front.className = 'single-face';
  const image = document.createElement('img');
  image.src = card.cover;
  image.alt = card.title;
  image.draggable = false;
  const sheen = document.createElement('div');
  sheen.className = 'image-sheen';
  sheen.style.mixBlendMode = 'screen';
  const reflection = document.createElement('div');
  reflection.className = 'image-reflection';
  reflection.style.cssText = 'position:absolute;inset:0;pointer-events:none;opacity:var(--foil,.52);mix-blend-mode:screen';
  front.append(image, sheen, reflection);
  const back = document.createElement('div');
  back.className = 'single-face single-back';
  const brand = document.createElement('strong');
  brand.textContent = '映光';
  const title = document.createElement('span');
  title.textContent = card.title;
  back.append(brand, title);
  surface.append(front, back);
  stage.append(surface);
  container.replaceChildren(stage);
  let auto = false, flipped = false, dragging = false, x = -2, y = -8, frameId, zoom = 1;
  let rotationSensitivity = 1, dragStart;
  const media = matchMedia('(prefers-reduced-motion: reduce)');
  const draw = () => {
    surface.style.transform = `rotateX(${x}deg) rotateY(${y + (flipped ? 180 : 0)}deg) scale(${zoom})`;
    const slopeX = -Math.tan(y * Math.PI / 180), slopeY = Math.tan(x * Math.PI / 180) / Math.cos(y * Math.PI / 180);
    const phase = slopeX * 1.35 - slopeY * .9, hue = phase * 360;
    const colors = surface.dataset.finish === 'gold' ? ['#c29b42c0', '#ffe3a3e0', '#c39a42c0'] : surface.dataset.finish === 'silver' ? ['#a4adb4b0', '#ffffffd0', '#c1ccd6b0'] : [`hsla(${hue + 285},96%,56%,.78)`, `hsla(${hue + 50},96%,62%,.8)`, `hsla(${hue + 190},96%,55%,.78)`];
    sheen.style.background = `linear-gradient(${120 + phase * 35}deg, transparent 2%, ${colors[0]} 24%, ${colors[1]} 48%, ${colors[2]} 74%, transparent 98%)`;
    sheen.style.backgroundSize = '150% 150%';
    sheen.style.backgroundPosition = `${50 + phase * 38}% ${50 - slopeY * 36}%`;
    const center = 50 + (slopeX * .65 - slopeY * .48) * 70;
    const white = surface.dataset.finish === 'gold' ? '255,237,190' : '245,251,255';
    reflection.style.background = `linear-gradient(120deg,transparent ${center - 14}%,rgba(${white},.7) ${center - 3}%,rgba(${white},1) ${center}%,rgba(${white},.7) ${center + 3}%,transparent ${center + 14}%)`;
    reflection.hidden = surface.dataset.finish === 'original';
  };
  const animate = time => {
    if (auto && !document.hidden) { x = Math.sin(time / 1800) * 4; y = Math.sin(time / 2300) * 15; draw(); }
    frameId = requestAnimationFrame(animate);
  };
  const stop = () => { auto = false; onInteraction({ auto, flipped }); };
  stage.addEventListener('pointerdown', event => {
    if (event.button !== 0) return;
    event.preventDefault();
    stage.focus({ preventScroll: true });
    dragging = true;
    stop();
    dragStart = { pointerX: event.clientX, pointerY: event.clientY, x, y };
    stage.setPointerCapture(event.pointerId);
  });
  stage.addEventListener('pointermove', event => {
    if (!dragging) return;
    const rect = stage.getBoundingClientRect();
    x = Math.max(-18, Math.min(18, dragStart.x - (event.clientY - dragStart.pointerY) / rect.height * 32 * rotationSensitivity));
    y = Math.max(-30, Math.min(30, dragStart.y + (event.clientX - dragStart.pointerX) / rect.width * 52 * rotationSensitivity));
    draw();
  });
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) stage.addEventListener(type, () => { dragging = false; });
  stage.addEventListener('wheel', event => { event.preventDefault(); zoom = Math.max(.8, Math.min(1.08, zoom - event.deltaY * .001)); draw(); }, { passive: false });
  stage.addEventListener('keydown', event => {
    if (!['ArrowUp', 'ArrowDown', 'f', 'F', ' '].includes(event.key)) return;
    event.preventDefault();
    if (event.key === ' ') auto = !auto;
    else if (event.key.toLowerCase() === 'f') { flipped = !flipped; auto = false; }
    else { x = Math.max(-20, Math.min(20, x + (event.key === 'ArrowUp' ? -4 : 4))); auto = false; }
    draw();
    onInteraction({ auto, flipped });
  });
  image.addEventListener('error', () => { image.alt = '图片无法打开，请重新导入'; });
  draw();
  frameId = requestAnimationFrame(animate);
  return {
    setRotationSensitivity(value) { rotationSensitivity = normalizeRotationSensitivity(value); return rotationSensitivity; },
    getState() { return { auto, flipped, rotationX: x, rotationY: y, rotationSensitivity, zoom }; },
    parameters(parameters) { surface.style.setProperty('--foil', parameters.foil); surface.style.setProperty('--image-scale', 1 / parameters.subjectScale); surface.dataset.finish = parameters.finish; draw(); },
    auto(value) { auto = !!value; },
    flip(value) { flipped = !!value; auto = false; draw(); },
    reset() { x = -2; y = -8; zoom = 1; flipped = false; auto = false; draw(); },
    destroy() { cancelAnimationFrame(frameId); },
    reducedMotion: media.matches,
  };
}
