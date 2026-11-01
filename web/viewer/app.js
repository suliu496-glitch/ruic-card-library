import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
// Icons are inline data trees (icons.data.js) — zero sub-imports at runtime,
// so no ad/privacy blocker can kill the page by blocking an icon module.
import { ICON_TREES } from "./icons.data.js";
const icons = ICON_TREES;
const $ = (id) => document.getElementById(id);
const stage = $("stage");
const media = matchMedia("(prefers-reduced-motion: reduce)");
const scene = new THREE.Scene();
const camera = new THREE.OrthographicCamera(-6, 6, 6, -6, 0.1, 100);
camera.position.set(0, 0, 20);
const inverse = new THREE.Matrix4();
const reliefLayers = { subject: [], effects: [], text: [] };
let renderer,
  root,
  uniforms,
  config,
  shadow,
  lastTime = 0,
  elapsed = 0;
let auto = false,
  flipped = false,
  dragging = false,
  finish = "pearl",
  zoom = 1;
let targetX = -0.035,
  targetY = -0.15,
  lastPointer = { x: 0, y: 0 },
  noticeTimer;
let layered = false, layerGap = 1, explodedGroup, cardScene, explodedWidth = 6.4;
const inspectionLayers = [];
const assembledVisibility = new Map();
let rotationSensitivity = 1;
function setRotationSensitivity(value) {
  rotationSensitivity = Number.isFinite(value) ? Math.round(Math.max(.25, Math.min(2, value)) * 20) / 20 : 1;
  return rotationSensitivity;
}
const settings = [
  ["foil", "uFoil"],
  ["scale", "uScale"],
  ["depth", "uDepth"],
  ["fx-depth", "uFxDepth"],
  ["bg-depth", "uBgDepth"],
];
const vertex = `
varying vec2 vUv;
void main() {
  vUv = vec2(uv.x, 1.0 - uv.y);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}
`;
const common = `
precision highp float;
varying vec2 vUv;
uniform float uTime, uFoil, uScale, uDepth, uBgDepth, uFinish, uHasLine, uRelief, uSafeScale, uFxDepth, uHasFx;
uniform vec2 uFit, uSafeOffset;
uniform vec3 uView;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1,311.7))) * 43758.5453); }
float inside(vec2 p) { return step(0.,p.x)*step(0.,p.y)*step(p.x,1.)*step(p.y,1.); }
vec2 viewingSlope() {
  return clamp(uView.xy / max(abs(uView.z), .45), vec2(-1.2), vec2(1.2));
}
vec2 parallax(vec2 uv, float depth) {
  // GLB top-edge V=0 is restored once by vertex; Texture.flipY=true means
  // the resulting sampled V points upwards, matching canonical card Y.
  // Keep the default depth more legible, while bounding extreme depth settings
  // before artwork-fit/scale so the image never travels across a large edge strip.
  // Vertical motion stays restrained for seated/grounded subjects and artwork
  // cropped at the top edge; horizontal separation carries the stronger depth.
  return uv + clamp(viewingSlope() * depth * vec2(.30,.14), vec2(-.075,-.035), vec2(.075,.035));
}
vec3 spectrum(float phase) {
  return .52 + .48 * cos(6.28318 * (phase + vec3(0., .33, .67)));
}
// Only "original" (uFinish ~ 2) disables the foil; pearl/silver/gold all use it.
float strength() { return abs(uFinish - 2.0) < 0.05 ? 0. : uFoil; }
vec3 film(vec2 uv) {
  float phase = uv.x * .85 + uv.y * .55 + dot(viewingSlope(), vec2(1.35, -.9));
  if (uFinish > 2.5) {
    // 烫金 (gold foil): warm gold laminate that shifts with the viewing angle.
    float hi = 0.5 + 0.5 * sin(phase * 6.28318);
    float glint = 0.5 + 0.5 * cos((phase + 0.25) * 6.28318);
    vec3 deep = vec3(.72, .50, .20);
    vec3 bright = vec3(1.00, .90, .60);
    return mix(deep, bright, hi * .7 + glint * .3);
  }
  vec3 color = spectrum(phase);
  return mix(color, vec3(dot(color,vec3(.2126,.7152,.0722))), step(.5,uFinish));
}
float sweep(vec2 uv) {
  return pow(.5+.5*sin((uv.x*.72+uv.y*.45+dot(viewingSlope(),vec2(1.25,-.65)))*6.283),2.6);
}
float reflection(vec2 uv) {
  float distance = abs(uv.x*.78 + uv.y*.44 - .61 - dot(viewingSlope(),vec2(.65,-.48)));
  return 1.-smoothstep(.018,.13,distance);
}
vec3 laminate(vec3 printColor, vec2 uv) {
  vec3 foil = film(uv);
  float amount = strength(), band = sweep(uv);
  float luminance = dot(printColor,vec3(.2126,.7152,.0722));
  // Broad colored interference and a separately moving white reflection.
  // A bounded reflection lifts print detail without clipping bright skin highlights.
  vec3 col = printColor * (1.-amount*(.24+.40*band)*(1.-foil)*.65);
  col += foil * amount * (.13+.28*band) * (1.-luminance*.55);
  float glint = reflection(uv);
  vec3 white = uFinish > 2.5 ? vec3(1.,.93,.72) : vec3(.96,.985,1.);
  col = mix(col,white,min(.88,amount*(glint*.65+pow(glint,6.)*.40)));
  vec2 cell = floor(uv*vec2(480.,720.));
  float flake = step(.992,hash(cell))*pow(.5+.5*sin(hash(cell+8.)*30.+dot(viewingSlope(),vec2(20.,-13.))),10.);
  col = mix(col,white,flake*amount*.38);
  return col;
}
`;
const frontFragment =
  common +
  `
uniform sampler2D tSubject, tBackground, tText, tLine, tEffects;
void main() {
  vec2 uv = vUv;
  vec2 su = ((parallax(uv,uDepth)-.5)*uScale/uFit+.5)*uSafeScale+uSafeOffset;
  vec2 bu = parallax(uv,uBgDepth);
  vec4 subject = texture2D(tSubject,clamp(su,0.,1.));
  // Extend an opaque cropped edge only for full-canvas art. Reference fits and
  // custom safe areas retain their intentional transparent border.
  bool fullCanvas = all(lessThan(abs(uFit-vec2(1.)),vec2(.0001)))
    && abs(uSafeScale-1.)<.0001 && length(uSafeOffset)<.0001;
  subject.a *= (fullCanvas ? 1. : inside(su))*(1.-uRelief);
  vec3 bg = texture2D(tBackground,clamp(bu,0.,1.)).rgb;
  vec3 col = mix(bg,subject.rgb,subject.a);
  if (uFinish > 2.5) col = col * vec3(1.02, .95, .78) + vec3(.05, .012, 0.0);
  // Effects layer floats between the subject and the text: above the character,
  // below the typography, with its own mid-depth parallax.
  vec2 eu = parallax(uv,uFxDepth);
  vec4 fx = texture2D(tEffects,clamp(eu,0.,1.));
  col = mix(col,fx.rgb,fx.a*(1.-uRelief)*uHasFx);
  float amount = strength();
  float band = sweep(uv);
  col = laminate(col,uv);
  float edge = 1.-smoothstep(.015,.06,min(min(uv.x,1.-uv.x),min(uv.y,1.-uv.y)));
  col = mix(col,film(uv)*.85+.15,edge*amount*.55);
  float line = (1.-smoothstep(.06,.25,texture2D(tLine,clamp(su,0.,1.)).r))*uHasLine;
  col += line*inside(su)*subject.a*band*amount*.10;
  vec4 text = texture2D(tText,uv);
  col = mix(col,text.rgb,text.a*(1.-uRelief));
  gl_FragColor = vec4(pow(clamp(col,0.,1.),vec3(2.2)),1.);
  #include <colorspace_fragment>
}
`;
const edgeFragment =
  common +
  `
void main() {
  vec3 col = mix(vec3(.66,.69,.67),film(vUv)*.6+.35,strength()*.7);
  gl_FragColor=vec4(pow(col,vec3(2.2)),1.);
  #include <colorspace_fragment>
}
`;
const backFragment =
  common +
  `
uniform sampler2D tBack;
void main() {
  vec2 uv=vec2(1.-vUv.x,vUv.y);
  vec4 art=texture2D(tBack,uv);
  vec3 col=vec3(.956,.961,.946);
  col*=1.-strength()*.12*(1.-film(vUv));
  col+=film(vUv)*sweep(vUv)*strength()*.055;
  col=mix(col,art.rgb,art.a);
  gl_FragColor=vec4(pow(clamp(col,0.,1.),vec3(2.2)),1.);
  #include <colorspace_fragment>
}
`;

const subjectFragment = common + `
uniform sampler2D tSubject;
void main() {
  // Relief cards use a separate subject mesh. Apply the same view-driven
  // depth offset as composited cards; mesh spacing alone is nearly invisible
  // on a phone. Sampling outside the source canvas stays transparent.
  vec2 su=parallax(vUv,uDepth);
  vec4 art=texture2D(tSubject,clamp(su,0.,1.));
  art.a*=inside(su);
  if(art.a<.06)discard;
  vec2 px=1./vec2(1024.,1630.);
  float inner=min(min(texture2D(tSubject,su+vec2(px.x*2.,0.)).a,texture2D(tSubject,su-vec2(px.x*2.,0.)).a),min(texture2D(tSubject,su+vec2(0.,px.y*2.)).a,texture2D(tSubject,su-vec2(0.,px.y*2.)).a));
  vec3 col=art.rgb;
  col=laminate(col,vUv);
  col=mix(col,vec3(.86,.72,.40),(1.-inner)*.22);
  gl_FragColor=vec4(pow(clamp(col,0.,1.),vec3(2.2)),art.a);
  #include <colorspace_fragment>
}
`;
const effectsFragment = common + `
uniform sampler2D tEffects;
void main() {
  vec2 eu=parallax(vUv,uFxDepth);
  vec4 art=texture2D(tEffects,clamp(eu,0.,1.));
  // The relief effects layer is a pre-cut RGBA asset: use its real alpha so
  // thorn/spark deco keeps its silhouette instead of a color-channel matte.
  float alpha=art.a*inside(eu);
  if(alpha<.015)discard;
  vec3 col=art.rgb;
  col=laminate(col,vUv);
  gl_FragColor=vec4(pow(clamp(col,0.,1.),vec3(2.2)),alpha);
  #include <colorspace_fragment>
}
`;
const textFragment = common + `
uniform sampler2D tText;
void main(){vec4 art=texture2D(tText,vUv);if(art.a<.02)discard;gl_FragColor=vec4(pow(art.rgb,vec3(2.2)),art.a);
  #include <colorspace_fragment>
}
`;

function canvasTexture(canvas) {
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.NoColorSpace;
  return texture;
}
function backTexture() {
  const c = document.createElement("canvas");
  c.width = 1024;
  c.height = 1536;
  const ctx = c.getContext("2d");
  ctx.strokeStyle = "#aeb5aa";
  ctx.lineWidth = 1.5;
  ctx.strokeRect(56, 56, 912, 1424);
  ctx.strokeRect(72, 72, 880, 1392);
  ctx.textAlign = "center";
  ctx.fillStyle = "#50594e";
  ctx.font = "500 420px Atelier, Georgia, serif";
  ctx.fillText((config.title || "A").slice(0, 1), 512, 846);
  ctx.font = "24px Arial";
  ctx.fillStyle = "#737b70";
  ctx.fillText(config.collection || "YINGGUANG COLLECTION", 512, 245);
  ctx.font = '34px "Songti SC", serif';
  ctx.fillText(config.subtitle || config.title, 512, 1020);
  ctx.font = "18px Arial";
  ctx.fillText(config.edition || "ART STUDY", 512, 1337);
  ctx.beginPath();
  ctx.moveTo(460, 1113);
  ctx.lineTo(564, 1113);
  ctx.stroke();
  return canvasTexture(c);
}
function addShadow() {
  const c = document.createElement("canvas");
  c.width = 256;
  c.height = 256;
  const ctx = c.getContext("2d");
  const grad = ctx.createRadialGradient(128, 128, 6, 128, 128, 128);
  grad.addColorStop(0, "rgba(29,35,25,0.13)");
  grad.addColorStop(0.4, "rgba(29,35,25,0.055)");
  grad.addColorStop(1, "rgba(29,35,25,0)");
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, 256, 256);
  shadow = new THREE.Mesh(
    new THREE.PlaneGeometry(8.8, 11.8),
    new THREE.MeshBasicMaterial({
      map: canvasTexture(c),
      transparent: true,
      depthWrite: false,
    }),
  );
  shadow.position.set(0.28, -0.48, -0.5);
  scene.add(shadow);
}
// Render a lucide node tree (["svg", attrs, [children]]) into an svg element.
function renderIconNode(node) {
  const [tag, attrs = {}, children = []] = node;
  const el = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, value);
  for (const child of children) el.appendChild(renderIconNode(child));
  return el;
}
function refreshIcons() {
  const overrides = { "stroke-width": 1.5 };
  // icons.data.js is keyed in PascalCase (Play, RotateCcw, SlidersHorizontal …) while
  // the markup uses lucide's hyphenated names (play, rotate-ccw, sliders-horizontal).
  // Looking the raw attribute up never matched, so no icon ever rendered.
  const pascal = (name) =>
    name.split("-").map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join("");
  document.querySelectorAll("[data-lucide]").forEach((el) => {
    const name = el.getAttribute("data-lucide");
    const tree = icons[name] || icons[pascal(name)];
    if (!tree) return;
    const [tag, defaults = {}, children = []] = tree;
    const svg = renderIconNode([tag, { ...defaults, ...overrides }, children]);
    el.replaceChildren(svg);
  });
}
function notice(message) {
  clearTimeout(noticeTimer);
  $("notice").textContent = message;
  $("notice").hidden = false;
  noticeTimer = setTimeout(() => ($("notice").hidden = true), 2600);
}
async function init() {
  refreshIcons();
  const requestedConfig = new URL(new URLSearchParams(location.search).get("config") || "./card-config.json", location.href);
  if (requestedConfig.origin !== location.origin || !/\.json$/i.test(requestedConfig.pathname)) throw Error("卡片配置地址无效");
  const response = await fetch(requestedConfig.href);
  if (!response.ok) throw Error("作品配置未找到");
  config = await response.json();
  if (!config || !config.assets || typeof config.assets !== "object") throw Error("卡片配置格式不正确");
  for (const [key, value] of Object.entries(config.assets)) {
    if (typeof value !== "string") throw Error("卡片资源路径不正确");
    const resource = new URL(value, requestedConfig);
    if (resource.origin !== location.origin || !/\.(png|jpe?g|webp|glb)$/i.test(resource.pathname)) throw Error("卡片包含不支持的资源");
    config.assets[key] = resource.href;
  }
  config.appearance = { ...(config.appearance || {}), background: "#f7f8fa" };
  if (!["pearl", "silver", "gold", "original"].includes(config.appearance.finish)) config.appearance.finish = "pearl";
  document.title = config.title + " · 映光卡匣";
  for (const [id, key] of [
    ["card-title", "title"],
    ["subtitle", "subtitle"],
    ["description", "description"],
    ["edition", "edition"],
    ["about-description", "description"],
    ["about-edition", "edition"],
  ])
    $(id).textContent = config[key] || "";
  $("about-title").textContent = [config.subtitle, config.title]
    .filter(Boolean)
    .join(" / ");
  await document.fonts.load("500 42px Atelier");
  try {
    renderer = new THREE.WebGLRenderer({
      antialias: true,
      alpha: false,
      preserveDrawingBuffer: true,
      powerPreference: "high-performance",
    });
  } catch (error) {
    // First retry with the most permissive context attributes — some setups
    // reject "high-performance" but accept the default.
    try {
      renderer = new THREE.WebGLRenderer({
        antialias: true,
        alpha: false,
        preserveDrawingBuffer: true,
        powerPreference: "default",
        failIfMajorPerformanceCaveat: false,
      });
    } catch (retryError) {
      // No WebGL at all (browser hardware acceleration off): switch to the
      // CSS-3D card — still layered 3D, just without the shader engine.
      fallback3D(retryError);
      return;
    }
  }
  renderer.setClearColor(config.appearance?.background || "#fafafa", 1);
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.NoToneMapping;
  stage.append(renderer.domElement);
  renderer.domElement.setAttribute("aria-hidden", "true");
  const textureLoader = new THREE.TextureLoader();
  const textures = await Promise.all(
    ["subject", "background", "text"].map((name) =>
      textureLoader.loadAsync(config.assets[name]),
    ),
  );
  const line = config.assets.lineart
    ? await textureLoader.loadAsync(config.assets.lineart)
    : new THREE.DataTexture(new Uint8Array([255, 255, 255, 255]), 1, 1);
  line.needsUpdate = true;
  // Optional effects overlay (sparks/thorn deco): drawn between subject and text.
  // A transparent 1x1 fallback keeps the front shader valid without it.
  const hasFx = !!config.assets.effects;
  const effects = hasFx
    ? await textureLoader.loadAsync(config.assets.effects)
    : new THREE.DataTexture(new Uint8Array([0, 0, 0, 0]), 1, 1);
  effects.colorSpace = THREE.NoColorSpace;
  if (!hasFx) effects.needsUpdate = true;
  [...textures, line, effects].forEach((t) => {
    t.colorSpace = THREE.NoColorSpace;
    t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
  });
  const p = config.parameters || {};
  const imageAspect = textures[0].image.width / textures[0].image.height;
  const fit =
    config.artworkFit ||
    (config.sourceMode === "reference"
      ? [
          Math.min(0.87, (0.87 * imageAspect) / (2 / 3)),
          Math.min(0.87, (0.87 * (2 / 3)) / imageAspect),
        ]
      : [1, 1]);
  uniforms = {
    tSubject: { value: textures[0] },
    tBackground: { value: textures[1] },
    tText: { value: textures[2] },
    tLine: { value: line },
    tEffects: { value: effects },
    tBack: { value: backTexture() },
    uTime: { value: 0 },
    uView: { value: new THREE.Vector3(0, 0, 1) },
    uFit: { value: new THREE.Vector2(...fit) },
    uFoil: { value: p.foil ?? 0.52 },
    uScale: { value: p.subjectScale ?? 1 },
    uDepth: { value: p.subjectDepth ?? 0.32 },
    uBgDepth: { value: p.backgroundDepth ?? -0.18 },
    uSafeScale: { value: config.safeArea?.scale ?? 1 },
    // The shader's V axis is flipped relative to Blender's UV space, so the
    // vertical safe-area offset needs a compensating transform (x is identical).
    uSafeOffset: {
      value: new THREE.Vector2(
        config.safeArea?.offset?.[0] ?? 0,
        1 - (config.safeArea?.scale ?? 1) - (config.safeArea?.offset?.[1] ?? 0),
      ),
    },
    uFxDepth: { value: p.effectsDepth ?? 0.14 },
    uHasFx: { value: hasFx ? 1 : 0 },
    uFinish: { value: 0 },
    uHasLine: { value: config.assets.lineart ? 1 : 0 },
    uRelief: { value: config.sourceMode === "relief" ? 1 : 0 },
  };
  const material = (fragment) =>
    new THREE.ShaderMaterial({
      uniforms,
      vertexShader: vertex,
      fragmentShader: fragment,
      side: THREE.FrontSide,
    });
  const materials = {
    web_front: material(frontFragment),
    web_back: material(backFragment),
    web_edge: material(edgeFragment),
    web_gold: new THREE.MeshBasicMaterial({ color: "#c9a24a" }),
  };
  for (const [role,fragment] of [["web_subject",subjectFragment],["web_effects",effectsFragment],["web_text",textFragment]]) {
    materials[role] = material(fragment);
    materials[role].transparent = true;
    materials[role].depthWrite = role !== "web_effects";
  }
  const gltf = await new GLTFLoader().loadAsync(config.assets.model);
  root = new THREE.Group();
  root.add(gltf.scene);
  scene.add(root);
  let faces = 0;
  gltf.scene.traverse((ob) => {
    if (!ob.isMesh) return;
    const role = ob.material?.name;
    if (role === "web_text" && config.sourceMode !== "relief") {
      ob.visible = false;
      return;
    }
    if (role === "web_front") faces++;
    ob.material = materials[role] || materials.web_edge;
    if (role === "web_subject") reliefLayers.subject.push(ob);
    if (role === "web_effects") reliefLayers.effects.push(ob);
    if (role === "web_text") reliefLayers.text.push(ob);
  });
  if (!faces) throw Error("卡片模型缺少正面材质");
  root.updateMatrixWorld(true);
  for (const meshes of Object.values(reliefLayers)) for (const mesh of meshes) {
    root.attach(mesh);
    mesh.userData.basePosition=mesh.position.clone();
    mesh.userData.baseScale=mesh.scale.clone();
  }
  if (config.sourceMode === "relief" && !reliefLayers.subject.length) throw Error("缺少独立人物层，请重新生成模型");
  addShadow();
  createExplodedLayers(textures, line, effects, hasFx, gltf.scene);
  const settingsHome=$('parameter-panel').parentElement;
  const responsiveSettings=()=>{
    const panel=$('parameter-panel');
    if(matchMedia('(max-width:960px)').matches)document.querySelector('main').append(panel);
    else settingsHome.append(panel);
  };
  responsiveSettings();window.addEventListener('resize',responsiveSettings);
  setupControls();
  document
    .querySelectorAll("button[disabled],input[disabled]")
    .forEach((el) => (el.disabled = false));
  new ResizeObserver(resize).observe(stage);
  resize();
  renderer.compile(scene, camera);
  renderer.render(scene, camera);
  const shaderErrors = (renderer.info.programs || []).filter(
    (p) => p.diagnostics && !p.diagnostics.runnable,
  );
  if (shaderErrors.length) {
    renderer.dispose();
    renderer.domElement.remove();
    fallback3D(new Error("当前设备无法显示卡面材质"));
    return;
  }
  $("loading").remove();
  root.rotation.set(targetX, targetY, 0);
  window.__holo = {
    ready: true,
    config,
    renderer,
    root,
    uniforms,
    camera,
    reset,
    flip,
    modelSource: config.assets.model,
    layers: reliefLayers,
    inspectionLayers,
    layerGroup: explodedGroup,
    setLayers: setLayered,
    setLayerGap,
    setRotationSensitivity,
    getOptics: opticsSnapshot,
    getState: () => ({ auto, flipped, finish, zoom, layered, layerGap, rotationSensitivity }),
  };
  setFinish(config.appearance?.finish || "pearl");
  setAuto(false);
  window.__libraryViewer = {
    mode: "webgl", hasEffects: hasFx, defaults: libraryDefaults(), ranges: libraryRanges(),
    setParameters(p) {
      for (const [key, id] of Object.entries(libraryInputs)) if (Number.isFinite(Number(p[key]))) {
        const input = $(id);
        input.value = Math.max(Number(input.min), Math.min(Number(input.max), Number(p[key])));
        updateInput(id, settings.find(([name]) => name === id)[1]);
      }
      if (["pearl", "silver", "gold", "original"].includes(p.finish)) setFinish(p.finish);
    },
    setAuto, flip, reset, setLayers: setLayered, setLayerGap, setRotationSensitivity,
    getState: () => ({ auto, flipped, finish, zoom, layered, layerGap, rotationSensitivity }),
  };
  renderer.setAnimationLoop(animate);
}
function createExplodedLayers(textures, line, effects, hasFx, model) {
  cardScene = model;
  const bounds = new THREE.Box3().setFromObject(model);
  const size = bounds.getSize(new THREE.Vector3());
  const center = bounds.getCenter(new THREE.Vector3());
  const height = size.y;
  const width = height * textures[0].image.width / textures[0].image.height;
  explodedWidth = width;
  explodedGroup = new THREE.Group();
  explodedGroup.name = "真实图层展示";
  explodedGroup.visible = false;
  root.add(explodedGroup);
  const definitions = [["background", "背景", textures[1]], ["subject", "人物", textures[0]], ["lineart", "线稿", line]];
  if (hasFx) definitions.push(["effects", "特效", effects]);
  definitions.push(["text", "文字", textures[2]]);
  definitions.forEach(([name, title, texture], index) => {
    let material;
    if (name === "lineart") {
      material = new THREE.ShaderMaterial({
        uniforms: { mask: { value: texture } }, transparent: true, depthWrite: false, side: THREE.DoubleSide,
        vertexShader: "varying vec2 vUv; void main(){ vUv=uv; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.); }",
        fragmentShader: "varying vec2 vUv; uniform sampler2D mask; void main(){ float ink=1.-smoothstep(.10,.70,texture2D(mask,vUv).r); if(ink<.03)discard; gl_FragColor=vec4(.83,.73,.51,ink*.65); }",
      });
    } else {
      const map = texture.clone();
      map.colorSpace = THREE.SRGBColorSpace;
      map.needsUpdate = true;
      material = new THREE.MeshBasicMaterial({ map, transparent: true, depthWrite: false, side: THREE.DoubleSide });
    }
    const group = new THREE.Group();
    group.name = `图层_${name}`;
    const plane = new THREE.Mesh(new THREE.PlaneGeometry(width, height), material);
    plane.name = `真实平面_${name}`;
    group.add(plane);
    const outline = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(-width / 2, -height / 2, .002), new THREE.Vector3(width / 2, -height / 2, .002),
      new THREE.Vector3(width / 2, height / 2, .002), new THREE.Vector3(-width / 2, height / 2, .002),
    ]), new THREE.LineBasicMaterial({ color: "#b59d6d", transparent: true, opacity: .4 }));
    group.add(outline);
    const canvas = document.createElement("canvas");
    canvas.width = 320; canvas.height = 80;
    const context = canvas.getContext("2d");
    context.fillStyle = "rgba(23,33,31,.96)"; context.fillRect(0, 0, 320, 80);
    context.strokeStyle = "#8e784e"; context.strokeRect(1, 1, 318, 78);
    context.fillStyle = "#e4d4b3"; context.font = '28px "Microsoft YaHei", sans-serif';
    context.textAlign = "center"; context.textBaseline = "middle";
    context.fillText(`${String(index + 1).padStart(2, "0")}  ${title}`, 160, 40);
    const labelTexture = new THREE.CanvasTexture(canvas);
    labelTexture.colorSpace = THREE.SRGBColorSpace;
    const label = new THREE.Sprite(new THREE.SpriteMaterial({ map: labelTexture, transparent: true, depthTest: false }));
    label.scale.set(1.45, .36, 1);
    label.renderOrder = 1000 + index;
    label.position.set(width / 2 + .85, height / 2 - .24 - index * .48, 0);
    group.add(label);
    group.position.x = center.x; group.position.y = center.y;
    explodedGroup.add(group);
    inspectionLayers.push({ name, title, group, plane });
  });
  layoutInspection();
}
function opticsSnapshot() {
  const view = uniforms.uView.value;
  const slope = [view.x, view.y].map(value => Math.max(-1.2, Math.min(1.2, value / Math.max(Math.abs(view.z), .45))));
  const sample = depth => slope.map((value, axis) => {
    const gain = axis === 0 ? .30 : .14, limit = axis === 0 ? .075 : .035;
    return Math.max(-limit, Math.min(limit, value * depth * gain));
  });
  const subject = sample(uniforms.uDepth.value).map((value, axis) => value * uniforms.uScale.value / uniforms.uFit.value.getComponent(axis) * uniforms.uSafeScale.value);
  return { view: view.toArray(), slope, phaseDelta: slope[0] * 1.35 - slope[1] * .9,
    subjectUvOffset: subject, backgroundUvOffset: sample(uniforms.uBgDepth.value), lineartUvOffset: [...subject], textUvOffset: [0, 0], parallaxStrength: .30, verticalParallaxStrength: .14, maximumRawUvOffset: .075, maximumVerticalRawUvOffset: .035,
    reflectionCenter: .61 + slope[0] * .65 - slope[1] * .48 };
}
function layoutInspection() {
  const p = config.parameters || {};
  inspectionLayers.forEach((layer, index) => {
    const role = { subject: ["uDepth", "subjectDepth", .32], lineart: ["uDepth", "subjectDepth", .32], background: ["uBgDepth", "backgroundDepth", -.18], effects: ["uFxDepth", "effectsDepth", .14] }[layer.name];
    const offset = role && uniforms ? (uniforms[role[0]].value - (p[role[1]] ?? role[2])) * .5 : 0;
    layer.group.position.z = (index - (inspectionLayers.length - 1) / 2) * layerGap + offset;
    const subjectScale = ["subject", "lineart"].includes(layer.name) && uniforms ? (p.subjectScale ?? 1) / uniforms.uScale.value : 1;
    layer.plane.scale.set(subjectScale, subjectScale, 1);
  });
}
function setLayerGap(value) {
  layerGap = Math.max(.25, Math.min(2, Number(value) || 1));
  layoutInspection();
  resize();
}
function setLayered(value) {
  const next = !!value;
  if (next !== layered) {
    if (next) {
      assembledVisibility.clear();
      // Relief meshes were root.attach'ed out of cardScene, so hide them too.
      // Snapshot each object's own flag; some card roles intentionally start hidden.
      for (const object of [cardScene, shadow, ...Object.values(reliefLayers).flat()].filter(Boolean)) {
        assembledVisibility.set(object, object.visible);
        object.visible = false;
      }
    } else {
      for (const [object, visible] of assembledVisibility) object.visible = visible;
      assembledVisibility.clear();
    }
  }
  layered = next;
  if (explodedGroup) explodedGroup.visible = layered;
  if (layered) {
    setAuto(false);
    targetX = -.10;
    targetY = (flipped ? Math.PI : 0) - .58;
  } else {
    targetX = -.035;
    targetY = (flipped ? Math.PI : 0) - .15;
  }
  resize();
}
// Layered 3D card built with CSS 3D transforms — used only when WebGL is
// unavailable (browser hardware acceleration off). It keeps the real depth
// stack: layers float at their configured offsets, the card tilts with the
// pointer, sways when idle, flips to a gold back, and the foil sheen follows
// the cursor. If WebGL comes back, the full shader engine takes over instead.
function fallback3D(error) {
  console.warn("[holo-card] WebGL unavailable, using CSS-3D fallback:", error);
  const roleZ = { background: -48, effects: -25, subject: -8, lineart: -8, text: 0 };
  const wrap = document.createElement("div");
  wrap.className = "fallback3d";
  const flipper = document.createElement("div");
  flipper.className = "flipper3d";
  const card = document.createElement("div");
  card.className = "card3d";
  card.id = "card3d";
  const front = document.createElement("div");
  front.className = "face3d front3d";
  const layers = new Map();
  for (const name of ["background", "effects", "subject", "lineart", "text"]) {
    if (!config?.assets?.[name]) continue;
    const layer = document.createElement("div");
    layer.className = "layer3d";
    const img = document.createElement("img");
    img.src = config.assets[name];
    img.alt = config.title || "卡片";
    img.draggable = false;
    img.loading = "eager";
    layer.append(img);
    front.append(layer);
    // White-background line art must not cover the subject: multiply drops the
    // white base and keeps only the dark contour strokes on top of the artwork.
    if (name === "lineart") {
      layer.style.mixBlendMode = "multiply";
      // This is a shader glint mask, not an ink drawing to paint over the art.
      layer.style.opacity = "0.06";
      img.addEventListener("load", () => {
        try {
          const canvas = document.createElement("canvas"); canvas.className = "lineart-mask";
          canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
          const context = canvas.getContext("2d"); context.drawImage(img, 0, 0);
          const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
          for (let offset = 0; offset < pixels.data.length; offset += 4) {
            const ink = (255 - pixels.data[offset]) / 255;
            pixels.data[offset] = 211; pixels.data[offset + 1] = 186; pixels.data[offset + 2] = 130;
            pixels.data[offset + 3] = Math.round(ink * 180);
          }
          context.putImageData(pixels, 0, 0); layer.replaceChildren(canvas);
          layer.style.mixBlendMode = "normal";
        } catch (maskError) { console.warn("Line mask fallback:", maskError); }
      });
    }
    layers.set(name, { el: layer, z: roleZ[name] });
  }
  const foil = document.createElement("div");
  foil.className = "foil3d";
  foil.style.mixBlendMode = "screen";
  front.append(foil);
  const reflection = document.createElement("div");
  reflection.className = "reflection3d";
  reflection.style.cssText = "position:absolute;inset:0;pointer-events:none;transform:translateZ(.05px);opacity:var(--foil-amount,.6);mix-blend-mode:screen";
  front.append(reflection);
  const back = document.createElement("div");
  back.className = "face3d back3d";
  const backMark = document.createElement("span");
  backMark.className = "back-mark";
  backMark.textContent = "映光";
  back.append(backMark);
  card.append(front, back);
  flipper.append(card);
  wrap.append(flipper);
  stage.append(wrap);
  $("loading").remove();

  // ---- interaction state (independent of the WebGL path) ----
  let tx = -0.03, ty = -0.06, curX = 0, curY = 0, curFlip = 0, flipTarget = 0;
  let lastMove = 0, sway = false;
  let fallbackDragging = false, dragStart;
  let scale = 1, depthScale = 1, bgScale = 1;
  const applyLayers = () => {
    const order = ["background", "subject", "lineart", "effects", "text"].filter(name => layers.has(name));
    for (const [name, { el, z }] of layers) {
      const s = name === "background" ? bgScale : 1;
      const position = layered ? (order.indexOf(name) - (order.length - 1) / 2) * layerGap * 58 : z * depthScale * s * (.30 / .14);
      el.style.transform = `translateZ(${position.toFixed(2)}px)`;
      el.dataset.layerLabel = { background: "01 背景", subject: "02 人物", lineart: "03 线稿", effects: "04 特效", text: `${String(order.length).padStart(2,"0")} 文字` }[name];
      el.style.setProperty("--label-top", `${order.indexOf(name) * 22}px`);
      if (name === "lineart") el.style.opacity = layered ? ".85" : ".06";
    }
  };
  const fallbackLayered = value => {
    layered = !!value; sway = false; auto = false;
    wrap.classList.toggle("layers-exploded", layered);
    tx = layered ? -.12 : -.03; ty = layered ? -.58 : -.06;
    applyLayers();
  };
  const fallbackGap = value => { layerGap = Math.max(.25, Math.min(2, Number(value) || 1)); applyLayers(); };
  applyLayers();
  stage.addEventListener("pointerdown", (e) => {
    if (e.button !== 0) return;
    e.preventDefault();
    fallbackDragging = true;
    sway = false; auto = false;
    dragStart = { pointerX: e.clientX, pointerY: e.clientY, x: tx, y: ty };
    stage.setPointerCapture(e.pointerId);
    stage.classList.add("dragging");
    stage.focus({ preventScroll: true });
  });
  stage.addEventListener("pointermove", (e) => {
    if (!fallbackDragging) return;
    const r = stage.getBoundingClientRect();
    tx = Math.max(-0.5, Math.min(0.5, dragStart.x + (e.clientY - dragStart.pointerY) / r.height * 0.9 * rotationSensitivity));
    const yawLimit = layered ? 1.1 : .5;
    ty = Math.max(-yawLimit, Math.min(yawLimit, dragStart.y + (e.clientX - dragStart.pointerX) / r.width * 1.1 * rotationSensitivity));
    lastMove = performance.now();
  });
  const releaseFallback = () => { fallbackDragging = false; stage.classList.remove("dragging"); };
  for (const type of ["pointerup", "pointercancel", "lostpointercapture"]) stage.addEventListener(type, releaseFallback);
  const frame = (now) => {
    if (sway && now - lastMove > 1500) {
      const t = now / 1000;
      tx = Math.sin(t * 0.7) * 0.07 + (layered ? -.12 : .05);
      ty = Math.sin(t * 0.55) * 0.11 - (layered ? .58 : .18);
    }
    curX += (tx - curX) * 0.08;
    curY += (ty - curY) * 0.08;
    curFlip += (flipTarget - curFlip) * 0.12;
    flipper.style.transform = `rotateX(${curX.toFixed(4)}rad) rotateY(${curY.toFixed(4)}rad) scale(${scale})`;
    card.style.transform = `rotateY(${curFlip.toFixed(4)}rad)`;
    const slopeX = Math.max(-1.2, Math.min(1.2, -Math.tan(curY)));
    const slopeY = Math.max(-1.2, Math.min(1.2, Math.tan(curX) / Math.cos(curY)));
    const phase = slopeX * 1.35 - slopeY * .9;
    const hue = phase * 360;
    const colors = finish === "gold" ? ["#c49c43c0", "#ffe3a3e0", "#ba8d3cc0"] : finish === "silver" ? ["#a4b0b8b0", "#ffffffd0", "#b7c6d4b0"] : [`hsla(${hue + 285},96%,56%,.78)`, `hsla(${hue + 50},96%,62%,.8)`, `hsla(${hue + 190},96%,55%,.78)`];
    foil.style.background = `linear-gradient(${120 + phase * 35}deg, transparent 2%, ${colors[0]} 24%, ${colors[1]} 48%, ${colors[2]} 74%, transparent 98%)`;
    foil.style.backgroundSize = "150% 150%";
    foil.style.backgroundPosition = `${50 + phase * 38}% ${50 - slopeY * 36}%`;
    const center = 50 + (slopeX * .65 - slopeY * .48) * 70;
    const white = finish === "gold" ? "255,237,190" : "245,251,255";
    reflection.style.background = `linear-gradient(120deg,transparent ${center - 14}%,rgba(${white},.7) ${center - 3}%,rgba(${white},1) ${center}%,rgba(${white},.7) ${center + 3}%,transparent ${center + 14}%)`;
    reflection.hidden = layered || finish === "original";
    front.style.setProperty("--mx", `${50 + slopeX * 30}%`);
    front.style.setProperty("--my", `${45 - slopeY * 30}%`);
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);

  // ---- control wiring (mirrors the WebGL controls) ----
  const setFlip = (value) => {
    flipped = value;
    sway = false;
    auto = false;
    flipTarget = flipped ? Math.PI : 0;
    if (layered) { tx = -.12; ty = -.58; }
    faceLabels();
  };
  const setAutoUI = (value) => {
    sway = value;
    auto = value;
    const b = $("auto");
    if (!b) return;
    b.setAttribute("aria-pressed", String(value));
    b.setAttribute("aria-label", value ? "暂停旋转" : "自动旋转");
    b.title = value ? "暂停旋转" : "自动旋转";
    const icon = document.createElement("i");
    icon.setAttribute("data-lucide", value ? "pause" : "play");
    b.replaceChildren(icon);
    refreshIcons();
  };
  const fallbackFinish = (value) => {
    finish = value;
    front.classList.remove("finish-gold", "finish-silver", "finish-pearl", "finish-original");
    front.classList.add("finish-" + value);
    document
      .querySelectorAll("[data-finish]")
      .forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.finish === value)));
    $("finish-name").textContent =
      { pearl: "珠光", silver: "银箔", gold: "烫金", original: "原画" }[value];
    $("foil").disabled = value === "original";
  };
  $("info").disabled = false;
  $("info").onclick = () => $("about").showModal();
  $("front").disabled = false;
  $("front").onclick = () => setFlip(false);
  $("back").disabled = false;
  $("back").onclick = () => setFlip(true);
  const depthToggleFallback = $("depth-toggle");
  if (depthToggleFallback) depthToggleFallback.onclick = () => toggleSettings();
  // The hide/show control cluster that used to sit under the card is gone: it rendered
  // as four unlabelled, icon-less circles there. Flipping stays available through the
  // 正面/背面 buttons and dragging already stops the idle sway, so only the panel's
  // permanent visibility matters here.
  const bindRange = (id, output, fn, decimals = 2) => {
    $(id).disabled = false;
    $(id).addEventListener("input", () => {
      const v = Number($(id).value);
      $(output).textContent = v.toFixed(decimals);
      fn(v);
    });
  };
  bindRange("scale", "scale-value", (v) => { scale = v; });
  bindRange("depth", "depth-value", (v) => {
    depthScale = Math.max(0.1, 1 + v * 4);
    applyLayers();
  });
  bindRange("fx-depth", "fx-depth-value", (v) => {
    const layer = layers.get("effects");
    if (layer) layer.el.style.transform = `translateZ(${(v * 64).toFixed(2)}px)`;
  });
  bindRange("bg-depth", "bg-depth-value", (v) => {
    bgScale = Math.max(0.1, 1 + v * 4);
    applyLayers();
  });
  document.querySelectorAll("[data-finish]").forEach((b) => {
    b.disabled = false;
    b.onclick = () => fallbackFinish(b.dataset.finish);
  });
  bindRange("foil", "foil-value", (v) => {
    front.style.setProperty("--foil-amount", v);
  }, 0);
  $("foil-value").textContent = Math.round(Number($("foil").value) * 100) + "%";
  front.style.setProperty("--foil-amount", $("foil").value);
  // Seed scale from config; depth sliders start neutral (the layered base
  // offsets above already encode the default depth profile).
  if (config.parameters?.subjectScale) {
    scale = config.parameters.subjectScale;
    $("scale").value = config.parameters.subjectScale;
  }
  applyLayers();
  fallbackFinish(config.appearance?.finish || "gold");
  notice("浏览器未开启 WebGL：已用轻量 3D 模式显示（层次保留）");
  window.__holo = { ready: false, error: String(error), fallback3d: true, config, layerElements: layers, setLayers: fallbackLayered, setLayerGap: fallbackGap, setRotationSensitivity, getState: () => ({ auto: sway, flipped, finish, zoom: scale, layered, layerGap, rotationSensitivity, rotationX: curX, rotationY: curY }) };
  window.__libraryViewer = {
    mode: "fallback", hasEffects: !!config.assets.effects, defaults: libraryDefaults(), ranges: libraryRanges(),
    setParameters(p) {
      for (const [key, id] of Object.entries(libraryInputs)) if (Number.isFinite(Number(p[key]))) {
        const input = $(id);
        input.value = Math.max(Number(input.min), Math.min(Number(input.max), Number(p[key])));
        input.dispatchEvent(new Event("input"));
      }
      if (["pearl", "silver", "gold", "original"].includes(p.finish)) fallbackFinish(p.finish);
    },
    setAuto: setAutoUI, flip: setFlip, setLayers: fallbackLayered, setLayerGap: fallbackGap, setRotationSensitivity,
    reset() { fallbackLayered(false); fallbackGap(1); tx = -.03; ty = -.06; scale = 1; setFlip(false); setAutoUI(false); },
    getState: () => ({ auto: sway, flipped, finish, zoom: scale, layered, layerGap, rotationSensitivity }),
  };
  $("close-about").onclick = () => $("about").close();
}
function resize() {
  if (!renderer) return;
  const width = stage.clientWidth,
    height = stage.clientHeight;
  const aspect = width / height;
  const halfHeight = Math.max(config.sourceMode === "relief" ? 6.25 : 5.45, (layered ? explodedWidth / 2 + 1.8 + layerGap : 4.5) / aspect) / zoom;
  camera.left = -halfHeight * aspect;
  camera.right = halfHeight * aspect;
  camera.top = halfHeight;
  camera.bottom = -halfHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(width, height);
}
function setAuto(value) {
  auto = value;
  const button = $("auto");
  if (!button) return;
  button.setAttribute("aria-pressed", String(auto));
  button.setAttribute("aria-label", auto ? "暂停旋转" : "自动旋转");
  button.title = auto ? "暂停旋转" : "自动旋转";
  button.replaceChildren();
  const icon = document.createElement("i");
  icon.setAttribute("data-lucide", auto ? "pause" : "play");
  button.append(icon);
  refreshIcons();
}
function setFinish(value) {
  finish = value;
  uniforms.uFinish.value = { pearl: 0, silver: 1, original: 2, gold: 3 }[value] ?? 3;
  document
    .querySelectorAll("[data-finish]")
    .forEach((b) =>
      b.setAttribute("aria-pressed", String(b.dataset.finish === value)),
    );
  $("finish-name").textContent = {
    pearl: "珠光",
    silver: "银箔",
    gold: "烫金",
    original: "原画",
  }[value];
  $("foil").disabled = value === "original";
}
function faceLabels() {
  $("front").setAttribute("aria-pressed", String(!flipped));
  $("back").setAttribute("aria-pressed", String(flipped));
  $("view-label").textContent = flipped ? "02 / BACK" : "01 / FRONT";
}
function flip(value = !flipped) {
  flipped = value;
  setAuto(false);
  targetY = (flipped ? Math.PI : 0) - (layered ? .58 : 0);
  targetX = layered ? -.10 : 0;
  faceLabels();
}
// Layered relief stack: clearly separated depths so the card reads as a
// lightbox diorama — subject / effects / text each float on their own plane
// (offsets in card-space units, card half-height ≈ 5.45).
const RELIEF_STEP = 0.22;
function layoutRelief() {
  const z = Number($("depth").value);
  const invScale = 1 / Number($("scale").value);
  const place = (meshes, dz) => {
    for (const mesh of meshes) {
      mesh.position.z = z + dz;
      mesh.scale.copy(mesh.userData.baseScale).multiplyScalar(invScale);
    }
  };
  place(reliefLayers.subject, 0);
  // Keep the exported layer separation, while allowing the effects slider to
  // move that layer independently of the subject.
  const fxOffset = (Number($("fx-depth").value) - (config.parameters?.effectsDepth ?? .14)) * .5;
  place(reliefLayers.effects, RELIEF_STEP + fxOffset);
  place(reliefLayers.text, RELIEF_STEP * 2);
}
function updateInput(id, name) {
  const input = $(id);
  uniforms[name].value = Number(input.value);
  if (config.sourceMode === "relief") layoutRelief();
  if (inspectionLayers.length) layoutInspection();
  $(id + "-value").value =
    id === "foil"
      ? Math.round(input.value * 100) + "%"
      : Number(input.value).toFixed(2);
}
function reset() {
  setLayered(false);
  setLayerGap(1);
  targetX = -0.035;
  targetY = -0.15;
  zoom = 1;
  flipped = false;
  setAuto(false);
  faceLabels();
  const p = config.parameters || {};
  const defaults = {
    foil: p.foil ?? 0.52,
    scale: p.subjectScale ?? 1,
    depth: p.subjectDepth ?? 0.32,
    "fx-depth": p.effectsDepth ?? 0.14,
    "bg-depth": p.backgroundDepth ?? -0.18,
  };
  settings.forEach(([id, name]) => {
    $(id).value = defaults[id];
    updateInput(id, name);
  });
  setFinish(config.appearance?.finish || "pearl");
  resize();
}
function toggleSettings(show = $("parameter-panel").hidden) {
  // The depth panel is shown by default and switched from the 景深调整 button next to
  // the finish control; no outside-click or Escape dismissal, so it only moves when
  // the button is pressed.
  const panel = $("parameter-panel");
  panel.hidden = !show;
  const button = $("depth-toggle");
  if (button) button.setAttribute("aria-expanded", String(show));
}
function setupControls() {
  if (config.sourceMode === "relief") {
    // Layered card relief: subject base plane 0..0.9, effects/text above it.
    $("depth").min="0.0";$("depth").max="0.9";
    $("scale").min="0.92";$("scale").max="1.3";
  }
  settings.forEach(([id, name]) => {
    $(id).value = uniforms[name].value;
    updateInput(id, name);
    $(id).addEventListener("input", () => updateInput(id, name));
  });
  stage.addEventListener("pointerdown", (e) => {
    if (e.button !== 0) return;
    e.preventDefault();
    dragging = true;
    setAuto(false);
    lastPointer = { x: e.clientX, y: e.clientY };
    stage.setPointerCapture(e.pointerId);
    stage.classList.add("dragging");
    stage.focus({ preventScroll: true });
  });
  stage.addEventListener("pointermove", (e) => {
    if (!dragging) return;
    const base = flipped ? Math.PI : 0;
    targetY = THREE.MathUtils.clamp(
      targetY + (e.clientX - lastPointer.x) * 0.006 * rotationSensitivity,
      base - 0.65,
      base + 0.65,
    );
    targetX = THREE.MathUtils.clamp(
      targetX + (e.clientY - lastPointer.y) * 0.004 * rotationSensitivity,
      -0.36,
      0.36,
    );
    lastPointer = { x: e.clientX, y: e.clientY };
  });
  const release = () => {
    dragging = false;
    stage.classList.remove("dragging");
  };
  ["pointerup", "pointercancel", "lostpointercapture"].forEach((type) =>
    stage.addEventListener(type, release),
  );
  stage.addEventListener(
    "wheel",
    (e) => {
      e.preventDefault();
      zoom = THREE.MathUtils.clamp(zoom - e.deltaY * 0.001, 0.82, 1.05);
      resize();
    },
    { passive: false },
  );
  stage.addEventListener("keydown", (e) => {
    if (
      ![
        "ArrowLeft",
        "ArrowRight",
        "ArrowUp",
        "ArrowDown",
        "f",
        "F",
        "r",
        "R",
        " ",
      ].includes(e.key)
    )
      return;
    e.preventDefault();
    if (e.key === " ") {
      setAuto(!auto);
      return;
    }
    if (e.key.toLowerCase() === "f") {
      flip();
      return;
    }
    if (e.key.toLowerCase() === "r") {
      reset();
      return;
    }
    setAuto(false);
    const base = flipped ? Math.PI : 0;
    if (e.key === "ArrowLeft") targetY -= 0.08;
    if (e.key === "ArrowRight") targetY += 0.08;
    if (e.key === "ArrowUp") targetX -= 0.06;
    if (e.key === "ArrowDown") targetX += 0.06;
    targetY = THREE.MathUtils.clamp(targetY, base - 0.65, base + 0.65);
    targetX = THREE.MathUtils.clamp(targetX, -0.36, 0.36);
  });
  $("front").onclick = () => flip(false);
  $("back").onclick = () => flip(true);
  const depthToggle = $("depth-toggle");
  if (depthToggle) depthToggle.onclick = () => toggleSettings();
  document
    .querySelectorAll("[data-finish]")
    .forEach((b) => (b.onclick = () => setFinish(b.dataset.finish)));
  // The depth panel is permanent: no toggle button, and no dismissal on an outside
  // click or Escape any more — the controls are meant to stay in view.
  $("info").onclick = () => $("about").showModal();
  $("close-about").onclick = () => $("about").close();
  $("about").onclick = (e) => {
    if (e.target === $("about")) {
      const r = $("about").getBoundingClientRect();
      if (
        e.clientX < r.left ||
        e.clientX > r.right ||
        e.clientY < r.top ||
        e.clientY > r.bottom
      )
        $("about").close();
    }
  };
  $("save").onclick = saveCard;
  media.addEventListener("change", () => {
    if (media.matches) setAuto(false);
  });
  renderer.domElement.addEventListener("webglcontextlost", (e) => {
    e.preventDefault();
    renderer.setAnimationLoop(null);
    notice("图形显示已暂停，请刷新页面恢复");
  });
}
function saveCard() {
  try {
    const originalSize = new THREE.Vector2();
    renderer.getSize(originalSize);
    const originalRatio = renderer.getPixelRatio();
    const bounds = {
      left: camera.left,
      right: camera.right,
      top: camera.top,
      bottom: camera.bottom,
    };
    renderer.setPixelRatio(1);
    renderer.setSize(1400, 1800, false);
    const captureHeight = config.sourceMode === "relief" ? 6.4 : 5.4;
    camera.left = -captureHeight*1400/1800;
    camera.right = captureHeight*1400/1800;
    camera.top = captureHeight;
    camera.bottom = -captureHeight;
    camera.updateProjectionMatrix();
    try {
      renderer.render(scene, camera);
      const link = document.createElement("a");
      link.download =
        (config.title || "art-card") +
        "-" +
        (flipped ? "back" : "front") +
        ".png";
      link.href = renderer.domElement.toDataURL("image/png");
      link.click();
      notice("卡片图片已保存");
    } finally {
      Object.assign(camera, bounds);
      camera.updateProjectionMatrix();
      renderer.setPixelRatio(originalRatio);
      renderer.setSize(originalSize.x, originalSize.y, false);
      renderer.render(scene, camera);
    }
  } catch (error) {
    console.error(error);
    notice("图片未能保存，请重试");
  }
}
function animate(now) {
  const dt = Math.min((now - lastTime) / 1000, 0.06) || 0;
  lastTime = now;
  if (document.hidden) return;
  if (auto) elapsed += dt;
  if (auto) {
    targetY = Math.sin(elapsed * 0.42) * 0.23 - (layered ? .58 : .055);
    targetX = Math.sin(elapsed * 0.53) * 0.055 - (layered ? .10 : .018);
  }
  const ease = media.matches ? 1 : 1 - Math.exp(-dt * 8);
  root.rotation.x += (targetX - root.rotation.x) * ease;
  root.rotation.y += (targetY - root.rotation.y) * ease;
  root.updateMatrixWorld(true);
  uniforms.uView.value
    .copy(camera.position)
    .applyMatrix4(inverse.copy(root.matrixWorld).invert())
    .normalize();
  uniforms.uTime.value = media.matches && !auto ? 0 : elapsed;
  shadow.scale.x = 1 - Math.abs(Math.sin(root.rotation.y)) * 0.14;
  renderer.render(scene, camera);
}
const fail = (message) => {
  const loading = $("loading");
  if (loading && window.__holo && window.__holo.ready) return;
  loading?.classList.add("error");
  loading?.setAttribute("role", "alert");
  loading?.replaceChildren();
  const msg = document.createElement("span");
  msg.textContent = message;
  const retry = document.createElement("button");
  retry.textContent = "重新加载";
  retry.onclick = () => location.reload();
  loading?.append(msg, retry);
  window.__holo = { ready: false, error: message };
};
const LOAD_TIMEOUT_MS = 12000;
let settled = false;
Promise.race([
  init().then(() => {
    settled = true;
  }),
  new Promise((_, reject) =>
    setTimeout(
      () => reject(new Error("卡片加载超时，请检查网络或刷新重试")),
      LOAD_TIMEOUT_MS,
    ),
  ),
]).catch((error) => {
  if (settled) return;
  console.error(error);
  fail("作品暂时无法加载。\n" + error.message);
});

const libraryInputs = { foil: "foil", subjectScale: "scale", subjectDepth: "depth", backgroundDepth: "bg-depth", effectsDepth: "fx-depth" };
function libraryDefaults() {
  return { foil: config.parameters?.foil ?? .52, subjectScale: config.parameters?.subjectScale ?? 1, subjectDepth: config.parameters?.subjectDepth ?? .32, backgroundDepth: config.parameters?.backgroundDepth ?? -.18, effectsDepth: config.parameters?.effectsDepth ?? .14, finish: config.appearance?.finish || "pearl" };
}
function libraryRanges() {
  return Object.fromEntries(Object.entries(libraryInputs).map(([key, id]) => [key, [Number($(id).min), Number($(id).max)]]));
}
