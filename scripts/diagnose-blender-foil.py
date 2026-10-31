"""Small controlled Blender renders used to identify print-obscuring foil nodes."""
from pathlib import Path
import json
import sys
import bpy

root = Path(sys.argv[sys.argv.index('--') + 1]).resolve()
scene = bpy.context.scene
tree = bpy.data.materials['01 · 主体 + 背景 / 核心合成'].node_tree
nodes = tree.nodes
controls = {
    'subjectOverlay': (nodes['叠加 · 主体镭射'], 0),
    'backgroundOverlay': (nodes['背景轻镭射'], 0),
    'stripeEmission': (nodes['条纹强度'], 1),
    'lineCoverage': (nodes['限制辉光覆盖 0.018'], 1),
    'lineStrength': (nodes['线描自发光 · 40'], 'Strength'),
    'starStrength': (nodes['闪星亮度'], 1),
}
original = {key: node.inputs[socket].default_value for key, (node, socket) in controls.items()}
scene.render.resolution_percentage = 50
scene.cycles.samples = 16
out = root.parents[1] / 'verification' / 'foil-diagnostics' / root.name
out.mkdir(parents=True, exist_ok=True)
variants = {
    'no-line': {'lineCoverage': 0},
    'no-stripe': {'stripeEmission': 0},
    'no-color-overlay': {'subjectOverlay': 0, 'backgroundOverlay': 0},
    'restrained-foil': {'subjectOverlay': .049, 'backgroundOverlay': .0378,
                        'stripeEmission': .035, 'lineCoverage': .000525,
                        'lineStrength': 40, 'starStrength': .4375},
}
for name, changes in variants.items():
    for key, value in original.items():
        node, socket = controls[key]
        node.inputs[socket].default_value = changes.get(key, value)
    scene.render.filepath = str(out / (name + '.png'))
    bpy.ops.render.render(write_still=True)
(out / 'node-isolation.json').write_text(json.dumps({'original': original, 'variants': variants}, indent=2), encoding='utf-8')
print('FOIL_NODE_ISOLATION_COMPLETE', out)
