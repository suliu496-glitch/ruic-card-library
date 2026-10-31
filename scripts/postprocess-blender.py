"""Apply print-preserving, config-driven foil strengths to generated RuiC scenes.

The upstream scene uses a fixed stripe emission even when card-config foil is
low. Its mapped background pattern then leaves large blue marks over faces.
Keep the view-dependent node graph, but give its additive emission a small
budget and drive the budgets from one editable foil property on the pivot.
"""
from pathlib import Path
import json
import shutil
import sys

import bpy


arguments = sys.argv[sys.argv.index('--') + 1:]
root = Path(arguments[0]).resolve()
config = json.loads((root / 'card-config.json').read_text(encoding='utf-8-sig'))
foil = max(0, min(1, float(config.get('parameters', {}).get('foil', .35))))
scene = bpy.context.scene
pivot = bpy.data.objects['转卡控制 · 播放时间线预览']
pivot['镭射强度'] = foil
pivot.id_properties_ui('镭射强度').update(min=0.0, max=1.0, description='与 card-config foil 一致；控制彩色镭射、稀疏线光和背景闪点。')
material = bpy.data.materials['01 · 主体 + 背景 / 核心合成']
nodes = material.node_tree.nodes
settings = [
    ('叠加 · 主体镭射', 0, .14),
    ('背景轻镭射', 0, .108),
    ('条纹强度', 1, .015),
    ('限制辉光覆盖 0.018', 1, .0015),
    ('闪星亮度', 1, 1.25),
]
if '特效轻镭射' in nodes:
    settings.append(('特效轻镭射', 0, .12))

before = {}
after = {}
for name, input_index, multiplier in settings:
    socket = nodes[name].inputs[input_index]
    before[name] = socket.default_value
    try:
        socket.driver_remove('default_value')
    except (TypeError, RuntimeError):
        pass
    socket.default_value = multiplier * foil
    driver = socket.driver_add('default_value').driver
    driver.type = 'SCRIPTED'
    variable = driver.variables.new()
    variable.name = 'foil'
    variable.targets[0].id = pivot
    variable.targets[0].data_path = '["镭射强度"]'
    driver.expression = f'{multiplier} * foil'
    after[name] = multiplier * foil

# Preserve the same sparse white contour emitter; its effective energy is
# constrained by the much smaller coverage mask instead of deleting line art.
nodes['线描自发光 · 40'].inputs['Strength'].default_value = 40
scene['视觉校正'] = '人物印刷细节优先：镭射强度统一关联控制器；条纹发光预算降低，保留角度驱动镭射、独立线稿和背景星点。'
scene.frame_set(25)
bpy.context.view_layer.update()
report = {
    'foil': foil,
    'before': before,
    'after': after,
    'upstreamFixedStrengths': {'subjectOverlay': .14, 'backgroundOverlay': .18,
                               'stripeEmission': .10, 'lineCoverage': .006,
                               'lineStrength': 40, 'starStrength': 5},
    'driverProperty': '转卡控制 · 播放时间线预览[镭射强度]',
    'viewDependentFoilRetained': True,
    'lineArtEmitterRetained': True,
    'backgroundStarsRetained': True,
    'parallaxAndGeometryChanged': False,
}
(root / 'blender-postprocess.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
bpy.ops.wm.save_as_mainfile(filepath=str(root / 'card.blend'))

if '--skip-render' not in arguments:
    reference_dir = root / 'renders' / 'before-postprocess'
    reference_dir.mkdir(parents=True, exist_ok=True)
    for name in ['hero.png', 'front.png', 'tilt-left.png', 'tilt-right.png']:
        old = root / 'renders' / name
        reference = reference_dir / name
        if old.exists() and not reference.exists():
            shutil.copy2(old, reference)
    scene.render.resolution_percentage = 100
    scene.cycles.samples = 32
    scene.render.filepath = str(root / 'renders' / 'hero.png')
    bpy.ops.render.render(write_still=True)
print('CONFIG_DRIVEN_PRINT_PRESERVING_FOIL_COMPLETE', root, json.dumps(after, ensure_ascii=False))
