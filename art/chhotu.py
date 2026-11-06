# Chhotu v2: Sahayak's own original furry 3D creature, built fully from code.
# Run with: blender -b -P chhotu.py -- <preview|clipname> <outdir>
import bpy, math, os, sys
import numpy as np
from mathutils import Vector, Matrix, Quaternion

args = sys.argv[sys.argv.index("--") + 1:]
MODE, OUT = args[0], args[1]
os.makedirs(OUT, exist_ok=True)
print("BLENDER", bpy.app.version_string)
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.render.engine = 'CYCLES'
scene.cycles.device = 'CPU'
scene.cycles.samples = 64 if MODE == 'preview' else 96
scene.cycles.use_adaptive_sampling = True
scene.cycles.adaptive_threshold = 0.02
scene.cycles.use_denoising = True
scene.cycles.denoiser = 'OPENIMAGEDENOISE'
scene.cycles.max_bounces = 6
scene.render.film_transparent = True
SIZE = 480
scene.render.resolution_x = scene.render.resolution_y = SIZE
scene.render.image_settings.file_format = 'PNG'
scene.render.image_settings.color_mode = 'RGBA'
try:
    scene.view_settings.view_transform = 'Standard'
except Exception:
    scene.view_settings.view_transform = 'Filmic'
scene.view_settings.exposure = -0.75

# ---------- materials ----------
def srgb(h):
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return tuple(x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c)

def put(p, names, value):
    for n in names:
        if n in p.inputs:
            p.inputs[n].default_value = value
            return

def mat(name, color, rough=0.45, sss=0.0, coat=0.0, emission=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    p = m.node_tree.nodes["Principled BSDF"]
    put(p, ["Base Color"], (*color, 1))
    put(p, ["Roughness"], rough)
    put(p, ["Subsurface Weight", "Subsurface"], sss)
    put(p, ["Subsurface Radius"], (0.6, 0.25, 0.2))
    put(p, ["Subsurface Scale"], 0.08)
    put(p, ["Coat Weight", "Clearcoat"], coat)
    if emission:
        put(p, ["Emission Color", "Emission"], (*color, 1))
        put(p, ["Emission Strength"], emission)
    return m

def fur(name, root, tip, lift=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    t = m.node_tree
    t.nodes.clear()
    out = t.nodes.new("ShaderNodeOutputMaterial")
    info = t.nodes.new("ShaderNodeHairInfo")
    ramp = t.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.0
    ramp.color_ramp.elements[0].color = (*root, 1)
    ramp.color_ramp.elements[1].position = 0.75
    ramp.color_ramp.elements[1].color = (*tip, 1)
    t.links.new(info.outputs["Intercept"], ramp.inputs[0])
    # every strand slightly different, like real fur
    spread = t.nodes.new("ShaderNodeMath"); spread.operation = 'MULTIPLY_ADD'
    spread.inputs[1].default_value = 0.42
    spread.inputs[2].default_value = 0.78
    t.links.new(info.outputs["Random"], spread.inputs[0])
    tint = t.nodes.new("ShaderNodeVectorMath"); tint.operation = 'SCALE'
    t.links.new(ramp.outputs[0], tint.inputs[0])
    t.links.new(spread.outputs[0], tint.inputs["Scale"])
    soft = t.nodes.new("ShaderNodeBsdfPrincipled")
    put(soft, ["Roughness"], 0.5)
    put(soft, ["Specular IOR Level", "Specular"], 0.2)
    put(soft, ["Sheen Weight", "Sheen"], 0.6)
    t.links.new(tint.outputs[0], soft.inputs["Base Color"])
    glow = t.nodes.new("ShaderNodeBsdfTranslucent")
    t.links.new(tint.outputs[0], glow.inputs["Color"])
    mix = t.nodes.new("ShaderNodeMixShader")
    mix.inputs[0].default_value = 0.3
    t.links.new(soft.outputs[0], mix.inputs[1])
    t.links.new(glow.outputs[0], mix.inputs[2])
    if lift:
        lit = t.nodes.new("ShaderNodeEmission")
        lit.inputs[1].default_value = lift
        t.links.new(tint.outputs[0], lit.inputs[0])
        both = t.nodes.new("ShaderNodeAddShader")
        t.links.new(mix.outputs[0], both.inputs[0])
        t.links.new(lit.outputs[0], both.inputs[1])
        t.links.new(both.outputs[0], out.inputs["Surface"])
    else:
        t.links.new(mix.outputs[0], out.inputs["Surface"])
    return m

skin = mat("skin", srgb("7A3CE0"), 0.6, sss=0.3)
skin_light = mat("skin_light", srgb("FFEBDC"), 0.6, sss=0.3)
fur_main = fur("fur_main", srgb("3A0FB8"), srgb("9A5CFF"))
fur_under = fur("fur_under", srgb("2A0A8C"), srgb("6A2EE0"))
fur_blush = fur("fur_blush", srgb("FF7FA8"), srgb("FFB3C6"), 0.08)
fur_light = fur("fur_light", srgb("F8DCC4"), srgb("FFF6EA"), 0.16)
mouth_in = mat("mouth_in", srgb("6E1636"), 0.55, sss=0.2)
tongue_m = mat("tongue", srgb("FF7C98"), 0.32, sss=0.6, coat=0.4)
tooth_m = mat("tooth", srgb("FFFBF0"), 0.2, coat=0.8)
nose_m = mat("nose", srgb("FF8A3C"), 0.18, sss=0.3, coat=1.0)
pad_m = mat("pad", srgb("FFB067"), 0.38, sss=0.5, coat=0.3)
brow_m = mat("brow", srgb("1C0A52"), 0.5, sss=0.2)
tip_m = mat("tip", srgb("FFA63D"), 0.25, sss=0.4, coat=0.7, emission=0.6)
stalk_m = mat("stalk", srgb("FFA63D"), 0.4, sss=0.4)
tear_m = mat("tear", srgb("9AD8FF"), 0.03, coat=1.0)
put(tear_m.node_tree.nodes["Principled BSDF"], ["Transmission Weight", "Transmission"], 0.85)
paper = mat("paper", srgb("FFFDF6"), 0.5, coat=0.15, emission=0.3)
flap_m = mat("flap", srgb("FFE9C9"), 0.5, coat=0.1, emission=0.25)
seal = mat("seal", srgb("E63946"), 0.2, coat=1.0)

def eye_material():
    m = bpy.data.materials.new("eye")
    m.use_nodes = True
    t = m.node_tree
    p = t.nodes["Principled BSDF"]
    put(p, ["Roughness"], 0.04)
    put(p, ["Coat Weight", "Clearcoat"], 1.0)
    put(p, ["Coat Roughness", "Clearcoat Roughness"], 0.02)
    coord = t.nodes.new("ShaderNodeTexCoord")
    sep = t.nodes.new("ShaderNodeSeparateXYZ")
    t.links.new(coord.outputs["Object"], sep.inputs[0])
    xx = t.nodes.new("ShaderNodeMath"); xx.operation = 'POWER'; xx.inputs[1].default_value = 2
    zz = t.nodes.new("ShaderNodeMath"); zz.operation = 'POWER'; zz.inputs[1].default_value = 2
    add = t.nodes.new("ShaderNodeMath"); add.operation = 'ADD'
    root = t.nodes.new("ShaderNodeMath"); root.operation = 'SQRT'
    t.links.new(sep.outputs[0], xx.inputs[0]); t.links.new(sep.outputs[2], zz.inputs[0])
    t.links.new(xx.outputs[0], add.inputs[0]); t.links.new(zz.outputs[0], add.inputs[1])
    t.links.new(add.outputs[0], root.inputs[0])
    # radial streaks in the iris
    ang = t.nodes.new("ShaderNodeMath"); ang.operation = 'ARCTAN2'
    t.links.new(sep.outputs[0], ang.inputs[0]); t.links.new(sep.outputs[2], ang.inputs[1])
    mul = t.nodes.new("ShaderNodeMath"); mul.operation = 'MULTIPLY'; mul.inputs[1].default_value = 6.0
    t.links.new(ang.outputs[0], mul.inputs[0])
    noise = t.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 2.5
    comb = t.nodes.new("ShaderNodeCombineXYZ")
    t.links.new(mul.outputs[0], comb.inputs[0])
    t.links.new(comb.outputs[0], noise.inputs["Vector"])
    ofs = t.nodes.new("ShaderNodeMath"); ofs.operation = 'MULTIPLY'; ofs.inputs[1].default_value = 0.09
    t.links.new(noise.outputs[0], ofs.inputs[0])
    plus = t.nodes.new("ShaderNodeMath"); plus.operation = 'ADD'
    t.links.new(root.outputs[0], plus.inputs[0]); t.links.new(ofs.outputs[0], plus.inputs[1])
    ramp = t.nodes.new("ShaderNodeValToRGB")
    cr = ramp.color_ramp
    stops = [(0.0, "050308"), (0.27, "07050C"), (0.31, "7A3E10"), (0.42, "F2B23A"), (0.54, "24D6B4"),
             (0.68, "0B8F86"), (0.77, "06222E"), (0.82, "F6F5FA"), (1.0, "E8E6F2")]
    cr.elements[0].position = stops[0][0]; cr.elements[0].color = (*srgb(stops[0][1]), 1)
    cr.elements[1].position = stops[-1][0]; cr.elements[1].color = (*srgb(stops[-1][1]), 1)
    for pos, col in stops[1:-1]:
        cr.elements.new(pos).color = (*srgb(col), 1)
    t.links.new(plus.outputs[0], ramp.inputs[0])
    # back of the eyeball stays white
    side = t.nodes.new("ShaderNodeMath"); side.operation = 'GREATER_THAN'; side.inputs[1].default_value = 0.0
    t.links.new(sep.outputs[1], side.inputs[0])
    mix = t.nodes.new("ShaderNodeMix"); mix.data_type = 'RGBA'
    t.links.new(side.outputs[0], mix.inputs[0])
    t.links.new(ramp.outputs[0], mix.inputs[6])
    mix.inputs[7].default_value = (*srgb("F6F5FA"), 1)
    t.links.new(mix.outputs[2], p.inputs["Base Color"])
    return m, cr

eye_m, eye_ramp = eye_material()
spark_m = mat("spark", (1, 1, 1), 0.4, emission=4.0)
lid_m = mat("lid", srgb("7A3CE0"), 0.5, sss=0.3)

# ---------- helpers ----------
def empty(name, parent=None, loc=(0, 0, 0)):
    o = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(o)
    o.parent = parent
    o.location = loc
    return o

def ball(name, material, loc, scale, parent=None, seg=48):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=seg // 2, location=(0, 0, 0))
    o = bpy.context.object
    o.name = name
    bpy.ops.object.shade_smooth()
    o.data.materials.append(material)
    o.parent = parent
    o.location = loc
    o.scale = scale if isinstance(scale, tuple) else (scale, scale, scale)
    return o

def new_mesh(name, verts, faces, parent, materials, smooth=True):
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], faces)
    me.update()
    if smooth:
        me.polygons.foreach_set("use_smooth", [True] * len(me.polygons))
    for m in materials:
        me.materials.append(m)
    o = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(o)
    o.parent = parent
    return o

def key_object(o):
    o.shape_key_add(name="Basis")
    k = o.shape_key_add(name="pose")
    k.value = 1.0
    return k

def set_key(k, verts):
    k.data.foreach_set("co", np.asarray(verts, dtype=np.float32).ravel())

def smooth01(x):
    x = np.clip(x, 0, 1)
    return x * x * (3 - 2 * x)

def add_fur(o, name, material, parents, kids, length, group_d=None, group_l=None, seed=1, droop=0.03, thick=0.0105):
    o.data.materials.append(material)
    slot = len(o.data.materials)
    o.modifiers.new(name, 'PARTICLE_SYSTEM')
    ps = o.particle_systems[-1]
    ps.seed = seed
    s = ps.settings
    s.type = 'HAIR'
    s.count = parents
    s.hair_length = length
    s.hair_step = 4
    s.render_step = 3
    s.display_step = 2
    s.emit_from = 'FACE'
    s.use_modifier_stack = False
    s.material = slot
    s.child_type = 'INTERPOLATED'
    s.child_percent = 2
    s.rendered_child_count = kids
    s.clump_factor = 0.5
    s.clump_shape = 0.2
    s.roughness_1 = 0.006
    s.roughness_endpoint = 0.012
    s.roughness_2 = 0.01
    s.tangent_factor = 0.0
    s.object_align_factor = (0, 0, -droop * 0.08)
    s.root_radius = 1.0
    s.tip_radius = 0.22
    s.radius_scale = thick
    s.use_close_tip = True
    if group_d:
        ps.vertex_group_density = group_d
    if group_l:
        ps.vertex_group_length = group_l

def add_group(o, name, weights):
    g = o.vertex_groups.new(name=name)
    for i, w in enumerate(weights):
        g.add([i], float(w), 'REPLACE')

# ---------- head: one mesh, the mouth is a real opening ----------
AX, AY, AZ = 1.32, 1.14, 1.16
M = np.array([0.0, -0.915, -0.40]); M /= np.linalg.norm(M)
U = np.array([1.0, 0.0, 0.0])
V = np.cross(M, U); V = V if V[2] > 0 else -V
EYE_DIR = {s: (lambda d: d / np.linalg.norm(d))(np.array([s * 0.47, -0.78, 0.34])) for s in (-1, 1)}
CHEEK = {s: (lambda d: d / np.linalg.norm(d))(np.array([s * 0.74, -0.62, -0.30])) for s in (-1, 1)}
NT, NR, NB = 240, 170, 12

def gauss(dirs, center, sigma):
    return np.exp(-((1 - np.clip(dirs @ center, -1, 1)) * 2) / (sigma * sigma))

def surface(dirs, smile=0.0, puff=0.0):
    r = 1 / np.sqrt((dirs[..., 0] / AX) ** 2 + (dirs[..., 1] / AY) ** 2 + (dirs[..., 2] / AZ) ** 2)
    r = r + 0.15 * gauss(dirs, M, 0.42)
    for s in (-1, 1):
        r = r + (0.05 + 0.08 * smile + 0.14 * puff) * gauss(dirs, CHEEK[s], 0.34)
        r = r - 0.13 * gauss(dirs, EYE_DIR[s], 0.27)
    return dirs * r[..., None]

def tangent(x, y):
    rho = np.maximum(np.hypot(x, y), 1e-6)
    d = (np.cos(rho)[..., None] * M +
         np.sin(rho)[..., None] * ((x / rho)[..., None] * U + (y / rho)[..., None] * V))
    return d

THETA = 2 * np.pi * np.arange(NT) / NT
RHO = 0.03 + (np.pi - 0.05) * np.linspace(0, 1, NR) ** 1.7

def lip_curve(p):
    c, s = np.cos(THETA), np.sin(THETA)
    x = p["mw"] * np.sign(c) * np.abs(c) ** 0.85
    mid = p["mc"] * (x / max(p["mw"], 0.01)) ** 2 + p["my"]
    h = np.where(s > 0, p["hu"], p["hl"])
    y = mid + np.sign(s) * np.abs(s) ** 0.8 * h + p.get("ms", 0.0) * x / max(p["mw"], 0.01)
    return x + p["mx"], y

def head_verts(p):
    lx, ly = lip_curve(p)
    th = THETA[None, :]
    rho = RHO[:, None]
    b = smooth01(rho / 1.0)
    px = rho * np.cos(th)
    py = rho * np.sin(th)
    x = lx[None, :] * (1 - b) + px * b
    y = ly[None, :] * (1 - b) + py * b
    d = tangent(x, y)
    pts = surface(d, p["smile"], p["puff"])
    rim = np.exp(-(rho - RHO[0]) / 0.045)
    bump = np.exp(-(((rho - 0.11) / 0.05) ** 2))
    pts = pts + d * (-0.05 * rim + 0.022 * bump)[..., None]
    lip = pts[0]
    center = lip.mean(axis=0)
    inner = center - M * 0.62
    rings = []
    for k in range(1, NB + 1):
        s = k / NB
        widen = 1.0 + 0.7 * math.sin(s * math.pi)
        ring = center + (lip - center) * (1 - s) * widen - M[None, :] * 0.62 * s
        rings.append(ring)
    all_pts = np.concatenate([pts.reshape(-1, 3), np.concatenate(rings), [inner], [pts[-1].mean(axis=0)]])
    return all_pts, center

def head_faces():
    faces, mats = [], []
    idx = lambda r, t: r * NT + (t % NT)
    for r in range(NR - 1):
        for t in range(NT):
            faces.append((idx(r, t), idx(r + 1, t), idx(r + 1, t + 1), idx(r, t + 1)))
            mats.append(0)
    # mouth bag: rings stored after the outer rings
    for k in range(NB - 1):
        a, b = (NR + k), (NR + k + 1)
        for t in range(NT):
            faces.append((idx(a, t), idx(b, t), idx(b, t + 1), idx(a, t + 1)))
            mats.append(1)
    for t in range(NT):
        faces.append((idx(0, t), idx(NR, t), idx(NR, t + 1), idx(0, t + 1)))
        mats.append(1)
    inner = (NR + NB) * NT
    back = inner + 1
    for t in range(NT):
        faces.append((idx(NR + NB - 1, t), inner, idx(NR + NB - 1, t + 1)))
        mats.append(1)
        faces.append((idx(NR - 1, t), back, idx(NR - 1, t + 1)))
        mats.append(0)
    return faces, mats

REST = dict(mw=0.27, mc=0.05, my=0.0, mx=0.0, hu=0.008, hl=0.008, smile=0.2, puff=0.0)
root = empty("root")
head = empty("head", root, (0, -0.1, 0.62))
hv, _ = head_verts(REST)
hf, hm = head_faces()
face = new_mesh("face", hv, hf, head, [skin, mouth_in, skin_light])
_u = hv / np.maximum(np.linalg.norm(hv, axis=1, keepdims=True), 1e-6)
_m = gauss(_u, M, 0.47)
hm = [2 if k == 0 and np.mean(_m[list(f)]) > 0.3 else k for f, k in zip(hf, hm)]
face.data.polygons.foreach_set("material_index", hm)
face.data.update()
face_key = key_object(face)

# fur density and length on the head
unit = hv / np.maximum(np.linalg.norm(hv, axis=1, keepdims=True), 1e-6)
ring = np.repeat(np.arange(NR), NT)
extra = len(hv) - len(ring)
rho_v = np.concatenate([RHO[ring], np.zeros(extra)])
keep = smooth01((rho_v - 0.09) / 0.1)
muzzle = gauss(unit, M, 0.47)
for s in (-1, 1):
    keep = keep * smooth01((1 - unit @ EYE_DIR[s]) * 2 / (0.36 * 0.36) - 0.65)
short = 0.28 + 0.72 * smooth01((rho_v - 0.1) / 0.75)
for s in (-1, 1):
    short = short * (0.35 + 0.65 * smooth01((1 - unit @ EYE_DIR[s]) * 2 / (0.62 * 0.62) - 0.2))
add_group(face, "main", keep * smooth01((0.72 - muzzle) / 0.5))
add_group(face, "pale", keep * smooth01((muzzle - 0.28) / 0.4))
add_group(face, "len", short)
cheek = np.maximum(gauss(unit, CHEEK[-1], 0.26), gauss(unit, CHEEK[1], 0.26))
add_group(face, "blush", keep * smooth01((cheek - 0.45) / 0.4))
add_fur(face, "head_under", fur_under, 9000, 22, 0.09, "main", "len", 21, 0.02, 0.015)
add_fur(face, "head_fur", fur_main, 15000, 24, 0.15, "main", "len", 2)
add_fur(face, "muzzle_fur", fur_light, 6000, 24, 0.10, "pale", "len", 3, 0.0)
add_fur(face, "blush_fur", fur_blush, 1500, 24, 0.105, "blush", "len", 22, 0.0, 0.009)

# ---------- eyes ----------
eye_c = {}
for s in (-1, 1):
    eye_c[s] = surface(EYE_DIR[s][None, :])[0] - EYE_DIR[s] * 0.235
R_EYE = 0.415
eyes = {}

def half_shell(name, parent, top):
    n, m = 48, 18
    verts, faces = [], []
    for i in range(m + 1):
        a = (i / m) * (math.pi / 2)
        for j in range(n):
            b = 2 * math.pi * j / n
            z = math.sin(a)
            rr = math.cos(a)
            verts.append((rr * math.cos(b), rr * math.sin(b), z if top else -z))
    for i in range(m):
        for j in range(n):
            a, b = i * n + j, i * n + (j + 1) % n
            c, d = (i + 1) * n + (j + 1) % n, (i + 1) * n + j
            faces.append((a, b, c, d))
    return new_mesh(name, verts, faces, parent, [lid_m])

for s in (-1, 1):
    e = empty("eye", head, tuple(eye_c[s]))
    e.rotation_euler = (0, 0, s * 0.16)
    gaze = empty("gaze", e)
    ball_o = ball("eyeball", eye_m, (0, 0, 0), R_EYE, gaze, 64)
    for x, z, r in ((0.13, 0.17, 0.082), (-0.11, -0.12, 0.04)):
        ball("catchlight", spark_m, (x, -math.sqrt(max(R_EYE ** 2 - x * x - z * z, 0)) * 1.005, z), (r, r * 0.16, r), e, 20)
    roll = empty("roll", e)
    up = empty("up", roll); low = empty("low", roll)
    upper = half_shell("upper", up, True); upper.scale = (R_EYE * 1.07,) * 3
    lower = half_shell("lower", low, False); lower.scale = (R_EYE * 1.07,) * 3
    eyes[s] = dict(gaze=gaze, roll=roll, up=up, low=low)

# ---------- brows, nose, mouth parts ----------
def curve(name, points, radii, depth, material, parent):
    c = bpy.data.curves.new(name, 'CURVE')
    c.dimensions = '3D'
    c.bevel_depth = depth
    c.bevel_resolution = 5
    c.use_fill_caps = True
    sp = c.splines.new('NURBS')
    sp.points.add(len(points) - 1)
    for pt, co, r in zip(sp.points, points, radii):
        pt.co = (*co, 1)
        pt.radius = r
    sp.use_endpoint_u = True
    sp.order_u = 3
    c.materials.append(material)
    o = bpy.data.objects.new(name, c)
    bpy.context.collection.objects.link(o)
    o.parent = parent
    return o, sp

def brow_points(s, inner, outer):
    e = EYE_DIR[s]
    a = np.linspace(-0.27, 0.27, 5)   # along the brow; negative is towards the nose
    pts = []
    for i, t in enumerate(a):
        lift = 0.31 + (inner if t < 0 else outer) * abs(t) / 0.27 + (inner + outer) * 0.5 * (1 - abs(t) / 0.27) + 0.05 * (1 - (t / 0.27) ** 2)
        d = e + np.array([s * t, 0.0, lift])
        d = d / np.linalg.norm(d)
        pts.append(tuple(surface(d[None, :])[0] + d * 0.135))
    return pts

brows = {}
for s in (-1, 1):
    o, sp = curve("brow", brow_points(s, 0, 0), [0.5, 1.05, 1.2, 1.0, 0.45], 0.088, brow_m, head)
    brows[s] = sp

nose_dir = M + V * 0.235; nose_dir /= np.linalg.norm(nose_dir)
nose = ball("nose", nose_m, tuple(surface(nose_dir[None, :])[0] + nose_dir * 0.035), (0.135, 0.09, 0.095), head)
tongue = ball("tongue", tongue_m, (0, 0, 0), (0.17, 0.2, 0.07), head)
teeth = []
for s in (-1, 1):
    bpy.ops.mesh.primitive_cube_add(size=1)
    t = bpy.context.object
    t.parent = head
    t.scale = (0.055, 0.03, 0.062)
    b = t.modifiers.new("b", 'BEVEL'); b.width = 0.18; b.segments = 4
    t.data.materials.append(tooth_m)
    bpy.ops.object.shade_smooth()
    teeth.append((s, t))

# antennae: part of the emotion
ant = {}
for s in (-1, 1):
    base = np.array([s * 0.40, 0.05, 0.96])
    pts = [tuple(base + np.array([s * 0.1 * i, 0, 0.17 * i])) for i in range(5)]
    o, sp = curve("antenna", pts, [1.3, 1.0, 0.85, 0.75, 0.7], 0.055, stalk_m, head)
    tip = ball("antenna_tip", tip_m, pts[-1], 0.15, head)
    ant[s] = (base, sp, tip)

tears = {s: ball("tear", tear_m, (0, 0, 0), (0.075, 0.06, 0.105), head, 24) for s in (-1, 1)}

# ---------- body, limbs ----------
def lathe_ellipsoid(a, b, c, nu=120, nv=90, pear=0.14):
    verts, faces = [], []
    for i in range(nv + 1):
        v = math.pi * i / nv
        for j in range(nu):
            u = 2 * math.pi * j / nu
            z = -math.cos(v)
            k = (1 + pear * (-z)) * math.sin(v)
            verts.append((a * k * math.cos(u), b * k * math.sin(u), c * z))
    for i in range(nv):
        for j in range(nu):
            p, q = i * nu + j, i * nu + (j + 1) % nu
            r, t = (i + 1) * nu + (j + 1) % nu, (i + 1) * nu + j
            faces.append((p, q, r, t))
    return np.array(verts), faces

bv, bf = lathe_ellipsoid(0.80, 0.74, 0.86)
body_o = new_mesh("body", bv, bf, root, [skin, skin_light])
body_o.location = (0, 0, -0.85)
ud = bv / np.linalg.norm(bv, axis=1, keepdims=True)
pale = np.exp(-((1 - np.clip(ud @ (np.array([0, -1, -0.15]) / np.linalg.norm([0, -1, -0.15])), -1, 1)) * 2) / (0.75 ** 2))
body_o.data.polygons.foreach_set("material_index", [1 if np.mean(pale[list(f)]) > 0.4 else 0 for f in bf])
body_o.data.update()
add_group(body_o, "main", smooth01((0.82 - pale) / 0.4))
add_group(body_o, "pale", smooth01((pale - 0.40) / 0.3))
add_fur(body_o, "body_under", fur_under, 6000, 22, 0.09, "main", None, 23, 0.02, 0.015)
add_fur(body_o, "body_fur", fur_main, 10000, 24, 0.15, "main", None, 4)
add_fur(body_o, "belly_fur", fur_light, 5000, 24, 0.11, "pale", None, 5)

NA, NC = 34, 22

def tube_verts(a, c, h, r0, r1):
    a, c, h = map(np.asarray, (a, c, h))
    out = []
    prev = np.array([1.0, 0, 0])
    for i in range(NA):
        t = i / (NA - 1)
        p = (1 - t) ** 2 * a + 2 * t * (1 - t) * c + t * t * h
        d = 2 * (1 - t) * (c - a) + 2 * t * (h - c)
        d = d / max(np.linalg.norm(d), 1e-6)
        side = np.cross(d, prev); side /= max(np.linalg.norm(side), 1e-6)
        up = np.cross(side, d)
        prev = up
        r = r0 + (r1 - r0) * t + 0.02 * math.sin(t * math.pi)
        for j in range(NC):
            w = 2 * math.pi * j / NC
            out.append(p + (side * math.cos(w) + up * math.sin(w)) * r)
    out.append(a)
    out.append(h)
    return np.array(out)

def tube_faces():
    f = []
    for i in range(NA - 1):
        for j in range(NC):
            f.append((i * NC + j, (i + 1) * NC + j, (i + 1) * NC + (j + 1) % NC, i * NC + (j + 1) % NC))
    s, e = NA * NC, NA * NC + 1
    for j in range(NC):
        f.append((j, (j + 1) % NC, s))
        f.append(((NA - 1) * NC + j, e, (NA - 1) * NC + (j + 1) % NC))
    return f

arms, hands = {}, {}
for s in (-1, 1):
    a = np.array([s * 0.74, -0.05, -0.42])
    h = np.array([s * 1.2, -0.25, -1.0])
    o = new_mesh("arm", tube_verts(a, (a + h) / 2, h, 0.25, 0.20), tube_faces(), root, [skin])
    add_fur(o, "arm_fur", fur_main, 2600, 24, 0.13, seed=6 + s)
    arms[s] = (o, key_object(o), a)
    hand = empty("hand", root, tuple(h))
    palm = ball("palm", skin, (0, 0, 0), (0.25, 0.21, 0.25), hand)
    add_fur(palm, "hand_fur", fur_main, 800, 20, 0.05, seed=8 + s)
    for ang in (-0.62, 0, 0.62):
        ball("finger", pad_m, (0.17 * math.sin(ang) * 1.3, -0.04, -0.20 * math.cos(ang)), (0.105, 0.105, 0.125), hand, 24)
    ball("thumb", pad_m, (-s * 0.21, -0.1, -0.02), (0.1, 0.1, 0.11), hand, 24)
    hands[s] = hand

for s in (-1, 1):
    a = np.array([s * 0.42, -0.05, -1.5])
    h = np.array([s * 0.5, -0.1, -1.92])
    o = new_mesh("leg", tube_verts(a, (a + h) / 2, h, 0.32, 0.3), tube_faces(), root, [skin])
    add_fur(o, "leg_fur", fur_main, 1300, 24, 0.13, seed=11 + s)
    foot = ball("foot", skin, (s * 0.54, -0.3, -2.0), (0.40, 0.56, 0.20), root)
    add_fur(foot, "foot_fur", fur_light, 1600, 20, 0.06, seed=13 + s)
    for ang in (-0.5, 0, 0.5):
        ball("toe", pad_m, (s * 0.54 + 0.3 * math.sin(ang), -0.3 - 0.54 * math.cos(ang), -2.03), (0.11, 0.1, 0.085), root, 24)

# letter
mail = empty("mail", root, (0, 0, 0))
mail.rotation_euler = (math.radians(90), math.radians(-9), 0)
bpy.ops.mesh.primitive_cube_add(size=1)
sheet = bpy.context.object
sheet.parent = mail
sheet.scale = (1.5, 1.02, 0.05)
b = sheet.modifiers.new("b", 'BEVEL'); b.width = 0.03; b.segments = 4
sheet.data.materials.append(paper)
flap = new_mesh("flap", [(-0.74, 0.5, 0.032), (0.74, 0.5, 0.032), (0, -0.14, 0.032)], [(0, 1, 2)], mail, [flap_m], False)
flap.location = (0, 0, 0.0)
stamp = ball("seal", seal, (0, -0.1, 0.05), (0.15, 0.15, 0.05), mail, 32)
mail_parts = [sheet, flap, stamp]

# original sunglasses: gold rim, deep glossy lenses
def plate(name, w, h, y, material, parent, bend=0.12, n=56):
    verts = [(0, y, 0)]
    for j in range(n):
        a = 2 * math.pi * j / n
        c, s_ = math.cos(a), math.sin(a)
        x = w * math.copysign(abs(c) ** 0.55, c)
        z = h * math.copysign(abs(s_) ** 0.62, s_) * (1.0 + 0.1 * (x / w))
        verts.append((x, y + bend * (x / w) ** 2 * 0.3, z))
    faces = [(0, j + 1, (j + 1) % n + 1) for j in range(n)]
    return new_mesh(name, verts, faces, parent, [material])

frame_m = mat("shade_frame", srgb("0B0B10"), 0.16, coat=1.0)
gold_m = mat("shade_gold", srgb("FFC247"), 0.2, coat=1.0)
gold_m.node_tree.nodes["Principled BSDF"].inputs["Metallic"].default_value = 1.0
lens_m = mat("shade_lens", srgb("080A14"), 0.04, coat=1.0)
shine_m = mat("shine", (1, 0.96, 0.82), 0.3, emission=14.0)
shades = empty("shades")
shades.rotation_mode = 'QUATERNION'
LENS_X = 0.52
shade_parts = []
for s in (-1, 1):
    pivot = empty("lens_at", shades, (s * LENS_X, 0, 0))
    pivot.rotation_euler = (0, 0, s * 0.14)
    shade_parts.append(plate("rim", 0.455, 0.355, 0.012, gold_m, pivot))
    shade_parts.append(plate("frame", 0.435, 0.335, 0.0, frame_m, pivot))
    shade_parts.append(plate("lens", 0.40, 0.30, -0.016, lens_m, pivot))
    hinge = Vector((s * 0.95, 0.03, 0.08))
    arm = bpy.data.curves.new("temple", 'CURVE')
    arm.dimensions = '3D'; arm.bevel_depth = 0.028; arm.bevel_resolution = 3
    sp = arm.splines.new('POLY'); sp.points.add(1)
    sp.points[0].co = (*hinge, 1); sp.points[1].co = (s * 1.18, 0.9, 0.12, 1)
    arm.materials.append(gold_m)
    o = bpy.data.objects.new("temple", arm); bpy.context.collection.objects.link(o); o.parent = shades
    shade_parts.append(o)
bridge = curve("bridge", [(-0.14, 0.0, 0.1), (-0.07, -0.015, 0.145), (0, -0.02, 0.155), (0.07, -0.015, 0.145), (0.14, 0.0, 0.1)],
               [1, 1, 1, 1, 1], 0.03, gold_m, shades)[0]
shade_parts.append(bridge)
glint = ball("glint", shine_m, (0, -0.04, 0), (0.07, 0.01, 0.07), shades, 20)
SHADE_LOCAL = Matrix.Translation((0, float(eye_c[1][1]) - R_EYE * 1.1, float(eye_c[1][2]) + 0.02))

# ---------- camera and light ----------
target = Vector((0, 0, -0.42))
cam_data = bpy.data.cameras.new("cam")
cam_data.lens = 70
cam_data.dof.use_dof = True
cam_data.dof.focus_distance = 10.2
cam_data.dof.aperture_fstop = 8
cam = bpy.data.objects.new("cam", cam_data)
bpy.context.collection.objects.link(cam)
cam.location = (1.5, -12.6, 0.9)
cam.rotation_mode = 'QUATERNION'
cam.rotation_quaternion = (target - cam.location).to_track_quat('-Z', 'Y')
scene.camera = cam

def area(loc, energy, size, color):
    d = bpy.data.lights.new("l", 'AREA')
    d.energy, d.size = energy, size
    d.color = color
    o = bpy.data.objects.new("l", d)
    bpy.context.collection.objects.link(o)
    o.location = loc
    o.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat('-Z', 'Y').to_euler()

area((4.5, -7, 6), 760, 5, (1, 0.93, 0.84))
area((-6, -5, 1), 260, 7, (0.9, 0.9, 1))
area((-4, 6, 5), 520, 3, (0.62, 0.78, 1))
area((5, 6, 0), 280, 3, (1, 0.72, 0.55))
area((0, -9, -3), 60, 8, (1, 0.9, 0.95))
scene.world = bpy.data.worlds.new("w")
scene.world.use_nodes = True
scene.world.node_tree.nodes["Background"].inputs[0].default_value = (0.8, 0.84, 1, 1)
scene.world.node_tree.nodes["Background"].inputs[1].default_value = 0.35

# ---------- emotions ----------
BASE = dict(REST, sf=None, glint=None, ulid=42.0, llid=-58.0, roll=0.0, bi=0.0, bo=0.0, gx=0.0, gz=0.0, pupil=0.31,
            hx=0.0, hy=0.0, hz=0.0, z=0.0, sq=1.0, lean=0.0, turn=0.0, droop=0.0, spread=0.0,
            tongue=0.0, tear=0.0, mail=False, teeth=True,
            la=(-1.18, -0.3, -1.0), ra=(1.18, -0.3, -1.0))

def blink(t, at, width=0.05):
    return float(np.exp(-(((t - at) / width) ** 2)))

def lerp(a, b, k):
    return a + (b - a) * k

def wave(a, b, k):
    return tuple(lerp(x, y, k) for x, y in zip(a, b))

VISEMES = [dict(mw=0.20, hu=0.05, hl=0.17), dict(mw=0.27, hu=0.03, hl=0.05), dict(mw=0.13, hu=0.06, hl=0.09),
           dict(mw=0.24, hu=0.04, hl=0.13), dict(mw=0.22, hu=0.008, hl=0.008), dict(mw=0.26, hu=0.04, hl=0.09)]

def talk(p, t):
    n = len(VISEMES)
    x = t * n
    i = int(x) % n
    k = float(smooth01(x - int(x)))
    a, b = VISEMES[i], VISEMES[(i + 1) % n]
    for key in a:
        p[key] = lerp(a[key], b[key], k)
    p["mc"] = 0.07
    p["smile"] = 0.5
    p["tongue"] = 0.5

def clip(name, t):
    p = dict(BASE)
    w = 2 * math.pi * t
    p["z"] = math.sin(w) * 0.035
    p["hz"] = math.sin(w) * 0.10
    p["hy"] = math.sin(w + 1) * 0.03
    p["gx"] = math.sin(w) * 0.05
    b = 0.0
    if name == 'idle':
        p.update(mc=0.075, mw=0.27, smile=0.45, llid=-50)
        b = blink(t, 0.88)
    elif name == 'happy':
        bounce = abs(math.sin(w * 2))
        p.update(mw=0.31, mc=0.11, hu=0.03, hl=0.17, smile=1.0, llid=-20, ulid=30, bi=0.05, bo=0.06,
                 tongue=0.7, z=bounce * 0.2, sq=1 - (1 - bounce) * 0.05, hx=-0.08, spread=0.25,
                 pupil=0.32, hz=math.sin(w) * 0.2, hy=math.sin(w) * 0.09)
        p["la"] = (-1.55, -0.2, 0.0 + bounce * 0.35)
        p["ra"] = (1.55, -0.2, 0.0 + (1 - bounce) * 0.35)
    elif name == 'excited':
        jump = abs(math.sin(w * 2)) ** 0.7
        p.update(mw=0.32, mc=0.08, hu=0.05, hl=0.27, smile=1.0, ulid=58, llid=-62, bi=0.14, bo=0.14,
                 tongue=1.0, z=jump * 0.26, sq=0.92 + jump * 0.1, pupil=0.36, spread=0.5, hx=-0.14,
                 hz=math.sin(w * 2) * 0.1)
        p["la"] = (-1.3, -0.3, 0.95 + jump * 0.15)
        p["ra"] = (1.3, -0.3, 0.95 + jump * 0.15)
    elif name == 'talk':
        talk(p, t)
        p.update(bi=0.05 + math.sin(w * 2) * 0.04, bo=0.04, hz=math.sin(w) * 0.13, hx=math.sin(w * 2) * 0.05)
        p["ra"] = (1.35, -0.55, -0.45 + math.sin(w * 2) * 0.22)
        b = blink(t, 0.72)
    elif name == 'mail':
        talk(p, t)
        p.update(mail=True, bi=0.12, bo=0.1, ulid=52, smile=0.8, hz=-0.12 + math.sin(w) * 0.05, gx=0.13, z=abs(math.sin(w * 2)) * 0.07)
        p["ra"] = (1.72, -0.62, 0.22 + math.sin(w * 2) * 0.05)
        b = blink(t, 0.45)
    elif name == 'sad':
        sob = math.sin(w * 2)
        p.update(mw=0.2, mc=-0.075, my=-0.02, hu=0.008, hl=0.014 + (sob * 0.5 + 0.5) * 0.012, smile=0.0, ulid=18 + sob * 3,
                 llid=-44, roll=0.32, bi=0.2, bo=-0.1, gz=-0.12, gx=0.0, pupil=0.34, hx=0.27, z=-0.08, sq=0.96,
                 droop=1.0, tear=(t * 2) % 1, hz=0.12, lean=0.05)
        p["la"] = (-0.95, -0.42, -1.2)
        p["ra"] = (0.95, -0.42, -1.2)
    elif name == 'surprised':
        s = math.sin(w * 4) * 0.012
        p.update(mw=0.12, mc=0.0, hu=0.10, hl=0.15, smile=0.2, ulid=66, llid=-70, bi=0.22, bo=0.2,
                 pupil=0.17, hx=-0.1, z=0.05 + s, hz=0.0, gx=0.0, spread=0.6, tongue=0.2)
        p["la"] = (-0.75, -1.0, 0.25)
        p["ra"] = (0.75, -1.0, 0.25)
    elif name == 'sleepy':
        nod = (1 - math.cos(w)) / 2
        yawn = float(np.exp(-(((t - 0.5) / 0.17) ** 2)))
        p.update(mw=lerp(0.15, 0.17, yawn), mc=0.02, hu=lerp(0.008, 0.10, yawn), hl=lerp(0.008, 0.2, yawn), smile=0.15,
                 ulid=lerp(6, -8, yawn), llid=-46, bi=lerp(0.02, 0.1, yawn), bo=-0.04, hx=0.12 + nod * 0.14 - yawn * 0.3,
                 hz=0.15, droop=0.65, tongue=yawn, gz=-0.1, z=-0.05)
        p["la"] = wave((-1.12, -0.42, -1.1), (-0.5, -1.12, 0.1), yawn)
    elif name == 'grumpy':
        p.update(mw=0.14, mc=-0.05, my=-0.02, hu=0.008, hl=0.012, smile=0.0, puff=0.8 + math.sin(w) * 0.2, ulid=24, llid=-34,
                 roll=-0.3, bi=-0.17, bo=0.12, pupil=0.2, hx=0.1, gx=0.16, hz=-0.2, droop=0.25, spread=-0.2)
        p["la"] = (-0.46, -0.92, -0.68)
        p["ra"] = (0.46, -0.92, -0.78)
        b = blink(t, 0.3)
    elif name == 'thinking':
        p.update(mw=0.15, mc=0.0, mx=0.05, my=0.0, hu=0.008, hl=0.01, smile=0.2, ulid=34, llid=-42, bi=0.06, bo=0.17,
                 gx=0.17, gz=0.16, hz=-0.15 + math.sin(w) * 0.03, hy=0.12, hx=-0.08, spread=0.2)
        p["ra"] = (0.58, -1.12, -0.04)
        b = blink(t, 0.6)
    elif name == 'swag':
        k = float(smooth01((t - 0.62) / 0.14))
        catch = float(np.exp(-(((t - 0.6) / 0.04) ** 2)))
        p.update(mw=lerp(0.16, 0.24, k), mc=lerp(0.03, 0.09, k), ms=lerp(0.015, 0.05, k), smile=lerp(0.3, 0.8, k), bi=-0.03, bo=0.16,
                 ulid=lerp(26, 22, k), llid=-40, gx=lerp(0.14, 0.0, k), pupil=0.27, z=-catch * 0.06, sq=1 - catch * 0.07,
                 hx=lerp(0.0, 0.02, k) + catch * 0.14 - float(smooth01((t - 0.3) / 0.2)) * (1 - k) * 0.2,
                 hz=lerp(0.12, -0.2, k), hy=lerp(-0.04, 0.09, k), spread=0.3, sf=float(np.clip((t - 0.28) / 0.34, 0, 1)),
                 glint=float(np.clip((t - 0.76) / 0.16, 0, 1)) if t > 0.76 else None)
        hold = (1.85, -0.55, 0.3)
        p["ra"] = wave(wave(hold, (1.5, -0.4, 1.25), float(smooth01((t - 0.24) / 0.1))), (1.1, -1.05, -0.2), k)
        p["la"] = (-1.32, -0.2, -0.82)
        p["spin"] = t * 9 * 2 * math.pi
    elif name in ('swagidle', 'swagtalk'):
        beat = math.sin(w * 2)
        if name == 'swagtalk':
            talk(p, t)
            p["hu"] *= 0.7; p["hl"] *= 0.7
        else:
            p.update(mw=0.24, mc=0.09, smile=0.8)
        p.update(ms=0.05, bo=0.16, bi=-0.03, ulid=22, llid=-40, sf=1.0, hz=-0.2 + math.sin(w) * 0.04, hy=0.09, hx=0.03 + abs(beat) * 0.07,
                 spread=0.3, z=abs(beat) * 0.025)
        p["ra"] = (1.1, -1.05, -0.2 + (math.sin(w * 2) * 0.1 if name == 'swagtalk' else 0.0))
        p["la"] = (-1.32, -0.2, -0.82)
    if b > 0:
        p["ulid"] = lerp(p["ulid"], -9, b)
        p["llid"] = lerp(p["llid"], -28, b)
        p["bo"] -= 0.05 * b
    return p

def apply(p, name):
    p = dict(p, mw=p["mw"] * 1.22, hu=p["hu"] * 1.3, hl=p["hl"] * 1.3)
    v, center = head_verts(p)
    set_key(face_key, v)
    root.location = (0, 0, p["z"])
    root.scale = (2 - p["sq"], 2 - p["sq"], p["sq"])
    root.rotation_euler = (p["lean"], 0, p["turn"])
    head.rotation_euler = (p["hx"], p["hy"], p["hz"])
    for s in (-1, 1):
        e = eyes[s]
        e["gaze"].rotation_euler = (p["gz"] * 3.2, 0, p["gx"] * 3.2)
        e["roll"].rotation_euler = (0, s * p["roll"], 0)
        e["up"].rotation_euler = (math.radians(-p["ulid"]), 0, 0)
        e["low"].rotation_euler = (math.radians(-p["llid"]), 0, 0)
        pts = brow_points(s, p["bi"], p["bo"])
        for pt, co in zip(brows[s].points, pts):
            pt.co = (*co, 1)
        base, sp, tip = ant[s]
        drop = p["droop"]
        spread = 0.1 + p["spread"] * 0.09
        cur = base.copy()
        ang = 0.0
        for i, pt in enumerate(sp.points):
            pt.co = (*cur, 1)
            ang += drop * 0.72 + p.get("lag", 0.0) * (i + 1) * 0.5
            cur = cur + np.array([s * (spread + math.sin(ang) * 0.17), 0, math.cos(ang) * 0.17])
        tip.location = tuple(np.array(sp.points[-1].co[:3]))
        fall = EYE_DIR[s] + np.array([s * 0.04, -0.05, -0.42 - p["tear"] * 0.42])
        fall = fall / np.linalg.norm(fall)
        tears[s].location = tuple(surface(fall[None, :])[0] + fall * 0.16)
        tears[s].hide_render = name != 'sad'
        o, key, a = arms[s]
        h = np.array(p["ra"] if s > 0 else p["la"])
        c = (a + h) / 2 + np.array([s * 0.28, 0.05, -0.12])
        set_key(key, tube_verts(a, c, h, 0.25, 0.20))
        hands[s].location = tuple(h)
        hands[s].rotation_euler = (0, 0, 0)
        up = h[2] > a[2]
        hands[s].rotation_euler = (math.radians(180) if up else 0, 0, 0)
    opening = p["hu"] + p["hl"]
    top = center + V * (p["my"] + p["hu"] * 0.55)
    low = center + V * (p["my"] - p["hl"] * 0.55)
    inside = -M
    tongue.location = tuple(low + inside * 0.3 + V * (0.02 + 0.05 * p["tongue"]))
    tongue.hide_render = opening < 0.05
    for s, tooth in teeth:
        tooth.location = tuple(center + V * (p["my"] + p["hu"] - 0.05) + U * s * 0.058 + inside * 0.08)
        tooth.hide_render = p["hu"] < 0.025 or opening < 0.08
    for part in mail_parts:
        part.hide_render = not p["mail"]
    mail.location = (p["ra"][0] - 0.08, p["ra"][1] - 0.32, p["ra"][2] + 0.42)
    pupil = p["pupil"]
    eye_ramp.elements[1].position = pupil
    eye_ramp.elements[2].position = pupil + 0.04

def place_shades(p):
    f = p["sf"]
    for part in shade_parts:
        part.hide_render = f is None
    glint.hide_render = p["glint"] is None
    if f is None:
        shades.matrix_world = Matrix.Translation((0, 0, 40))
        return
    on = head.matrix_world @ SHADE_LOCAL
    goal, turn, _ = on.decompose()
    grip = hands[1].matrix_world.translation + Vector((-0.12, -0.28, 0.2))
    top = (grip + goal) / 2 + Vector((0.1, -0.2, 1.25))
    if f <= 0:
        pos = grip
        rot = Quaternion((0, 0, 1), p.get("spin", 0.0))
    elif f >= 1:
        pos, rot = goal, turn
    else:
        pos = grip * (1 - f) ** 2 + top * (2 * f * (1 - f)) + goal * f * f
        rot = Quaternion((1, 0, 0), (1 - f) * 2 * math.pi * 2).slerp(turn, float(smooth01((f - 0.45) / 0.55)))
    shades.matrix_world = Matrix.Translation(pos) @ rot.to_matrix().to_4x4()
    if p["glint"] is not None:
        g = p["glint"]
        glint.location = (-0.85 + g * 1.7, -0.05, 0.1)
        r = 0.075 * math.sin(g * math.pi)
        glint.scale = (r, 0.01, r)

def render(path, name, t):
    p = clip(name, t)
    # antennae trail the body, so motion feels alive
    p["lag"] = (clip(name, t - 0.03)["z"] - p["z"]) * 2.2 + (clip(name, t - 0.03)["hz"] - p["hz"]) * 0.5
    apply(p, name)
    bpy.context.view_layer.update()
    place_shades(p)
    bpy.context.view_layer.update()
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)

if MODE == 'preview':
    wanted = [('idle', 0.2), ('happy', 0.25), ('swagidle', 0.1)]
    for i, (name, t) in enumerate(wanted):
        render(f"{OUT}/{i}_{name}.png", name, t)
else:
    N = 54 if MODE == 'swag' else 36
    for i in range(N):
        render(f"{OUT}/{MODE}_{i:02d}.png", MODE, i / N)
