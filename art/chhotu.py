# Chhotu: Sahayak's own original 3D character. Run with: blender -b -P chhotu.py -- <mode> <outdir>
import bpy, bmesh, math, os, sys
from mathutils import Vector

args = sys.argv[sys.argv.index("--") + 1:]
MODE, OUT = args[0], args[1]
os.makedirs(OUT, exist_ok=True)
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.render.engine = 'CYCLES'
scene.cycles.device = 'CPU'
scene.cycles.samples = 96
try:
    scene.cycles.use_denoising = True
except Exception:
    pass
scene.render.film_transparent = True
scene.render.resolution_x = scene.render.resolution_y = 512 if MODE == 'preview' else 384
scene.render.image_settings.file_format = 'PNG'
scene.render.image_settings.color_mode = 'RGBA'
scene.view_settings.view_transform = 'Filmic'
scene.view_settings.look = 'High Contrast'
scene.view_settings.exposure = -0.2

def mat(name, color, rough=0.45, sss=0.0, sheen=0.0, coat=0.0, metal=0.0, radius=(0.5, 0.25, 0.15)):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    p = m.node_tree.nodes["Principled BSDF"]
    def put(names, value):
        for n in names:
            if n in p.inputs:
                p.inputs[n].default_value = value
                return
    put(["Base Color"], (*color, 1))
    put(["Roughness"], rough)
    put(["Metallic"], metal)
    put(["Subsurface Weight", "Subsurface"], sss)
    put(["Subsurface Radius"], radius)
    put(["Sheen Weight", "Sheen"], sheen)
    put(["Coat Weight", "Clearcoat"], coat)
    return m

def srgb(h):
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return tuple(x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c)

purple = mat("fur", srgb("5A3DF0"), 0.48, sss=0.35, sheen=0.9, coat=0.15)
purple_light = mat("muzzle", srgb("7C63FF"), 0.45, sss=0.45, sheen=0.7, coat=0.15)
orange = mat("orange", srgb("FF9A2E"), 0.4, sss=0.4, sheen=0.5, coat=0.25, radius=(0.7, 0.3, 0.1))
belly = mat("belly", srgb("BFE6FF"), 0.5, sss=0.5, sheen=0.8)
white = mat("eye", srgb("FBFCFF"), 0.08, coat=1.0, sss=0.05)
iris = mat("iris", srgb("18C8A8"), 0.15, coat=1.0)
pupil = mat("pupil", srgb("07070F"), 0.04, coat=1.0)
mouth_dark = mat("mouth", srgb("3A0820"), 0.75)
tongue_m = mat("tongue", srgb("FF6F91"), 0.35, sss=0.6)
tooth_m = mat("tooth", srgb("FBFAF2"), 0.2, coat=0.7)
paper = mat("paper", srgb("F7F3EA"), 0.55, coat=0.15)
seal = mat("seal", srgb("E63946"), 0.2, coat=1.0)

def empty(name, parent=None, loc=(0, 0, 0)):
    o = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(o)
    o.parent = parent
    o.location = loc
    return o

def ball(name, material, loc, scale, parent=None, seg=64):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=seg // 2, location=(0, 0, 0))
    o = bpy.context.object
    o.name = name
    bpy.ops.object.shade_smooth()
    o.data.materials.append(material)
    o.parent = parent
    o.location = loc
    o.scale = scale if isinstance(scale, tuple) else (scale, scale, scale)
    return o

def meta(name, material, parts, parent, res=0.035):
    data = bpy.data.metaballs.new(name)
    data.resolution = data.render_resolution = res
    data.threshold = 0.6
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    for loc, size, radius in parts:
        el = data.elements.new(type='ELLIPSOID')
        el.co = loc
        el.size_x, el.size_y, el.size_z = size
        el.radius = radius
        el.stiffness = 2.2
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    bpy.ops.object.convert(target='MESH')
    mesh = bpy.context.object
    mesh.name = name
    bpy.ops.object.shade_smooth()
    mesh.data.materials.append(material)
    sm = mesh.modifiers.new("smooth", 'SMOOTH')
    sm.iterations = 8
    bpy.ops.object.select_all(action='DESELECT')
    mesh.parent = parent
    return mesh

root = empty("root")
body = meta("body", purple, [((0, 0, -0.75), (1.0, 0.85, 0.95), 1.45), ((0, 0, -1.25), (0.95, 0.8, 0.7), 1.25)], root)
add_belly = ball("belly", belly, (0, -0.62, -0.92), (0.74, 0.5, 0.74), root)
for s in (-1, 1):
    ball("foot", orange, (s * 0.6, -0.28, -1.92), (0.5, 0.68, 0.27), root)

head = empty("head", root, (0, -0.1, 0.72))
ball("skull", purple, (0, 0, 0), (1.25, 1.05, 1.1), head)
ball("muzzle", purple_light, (0, -0.78, -0.3), (0.76, 0.55, 0.52), head)

def rod(name, material, a, b, radius, parent):
    a, b = Vector(a), Vector(b)
    bpy.ops.mesh.primitive_cylinder_add(vertices=32, radius=radius, depth=(b - a).length, location=(a + b) / 2)
    o = bpy.context.object
    o.name = name
    o.rotation_mode = 'QUATERNION'
    o.rotation_quaternion = (b - a).to_track_quat('Z', 'Y')
    bpy.ops.object.shade_smooth()
    o.data.materials.append(material)
    o.parent = parent
    return o

# Antennae instead of ears, so Chhotu is its own character
for s_ in (-1, 1):
    rod("antenna", orange, (s_ * 0.42, 0, 0.9), (s_ * 0.86, 0, 1.55), 0.055, head)
    ball("antenna_tip", orange, (s_ * 0.9, 0, 1.62), 0.17, head)

eyes = []
for s in (-1, 1):
    e = empty("eye", head, (s * 0.5, -0.8, 0.2))
    ball("sclera", white, (0, 0, 0), (0.41, 0.3, 0.47), e)
    look = empty("look", e, (0, -0.25, 0))
    ball("iris", iris, (0, 0, 0), (0.25, 0.09, 0.27), look)
    ball("pupil", pupil, (0, -0.06, 0), (0.15, 0.06, 0.17), look)
    ball("spark1", white, (0.08, -0.13, 0.1), 0.055, look, 24)
    ball("spark2", white, (-0.08, -0.13, -0.08), 0.026, look, 24)
    lid = ball("lid", purple, (0, 0, 0), (0.435, 0.33, 0.5), e)
    bm = bmesh.new()
    bm.from_mesh(lid.data)
    bmesh.ops.bisect_plane(bm, geom=bm.verts[:] + bm.edges[:] + bm.faces[:], plane_co=(0, 0, 0), plane_no=(0, 0, 1), clear_inner=True)
    bm.to_mesh(lid.data)
    bm.free()
    eyes.append((look, lid))

brows = []
for s in (-1, 1):
    b = ball("brow", orange, (s * 0.5, -0.76, 0.74), (0.27, 0.075, 0.065), head)
    b.rotation_euler = (0, s * 0.14, 0)
    brows.append(b)
ball("nose", orange, (0, -1.31, -0.12), (0.14, 0.1, 0.1), head)

mouth = empty("mouth", head, (0, -1.27, -0.5))
ball("mouth_in", mouth_dark, (0, 0, 0), (0.36, 0.08, 0.16), mouth)
tongue = ball("tongue", tongue_m, (0, -0.05, -0.08), (0.19, 0.07, 0.075), mouth)
teeth = ball("teeth", tooth_m, (0, -0.06, 0.1), (0.21, 0.05, 0.045), mouth)

arms = []
for s in (-1, 1):
    a = empty("arm", root, (s * 0.9, -0.05, -0.3))
    ball("limb", purple, (0, 0, -0.42), (0.25, 0.25, 0.56), a)
    ball("hand", orange, (0, -0.03, -0.98), 0.28, a)
    a.rotation_euler = (0.2, -s * 0.5, 0)
    arms.append(a)

mail = empty("mail", root, (1.6, -0.42, 0.62))
mail.rotation_euler = (math.radians(90), math.radians(-12), 0)
bpy.ops.mesh.primitive_cube_add(size=1)
sheet = bpy.context.object
sheet.parent = mail
sheet.scale = (1.3, 0.85, 0.05)
bev = sheet.modifiers.new("bevel", 'BEVEL')
bev.width = 0.03
bev.segments = 4
sheet.data.materials.append(paper)
bpy.ops.mesh.primitive_cone_add(vertices=4, radius1=0.92, depth=0.5)
flap = bpy.context.object
flap.parent = mail
flap.location = (0, 0.16, 0.05)
flap.rotation_euler = (math.radians(180), 0, math.radians(45))
flap.scale = (1, 1, 0.09)
flap.data.materials.append(paper)
ball("seal", seal, (0, 0.05, 0.12), (0.14, 0.14, 0.06), mail, 32)
flap.hide_render = True
edge = mat("edge", srgb("A9B4C8"), 0.5)
for x in (-1, 1):
    rod("fold", edge, (x * 0.64, 0.41, 0.045), (0, -0.02, 0.045), 0.014, mail)
mail_parts = [sheet] + [c for c in mail.children if c.name.startswith("seal") or c.name.startswith("fold")]

cam_data = bpy.data.cameras.new("cam")
cam_data.lens = 64
cam = bpy.data.objects.new("cam", cam_data)
bpy.context.collection.objects.link(cam)
cam.location = (0.2, -10.6, 0.02)
cam.rotation_euler = (math.radians(89.6), 0, 0)
scene.camera = cam

def area(loc, energy, size, color):
    d = bpy.data.lights.new("l", 'AREA')
    d.energy, d.size = energy, size
    d.color = color
    o = bpy.data.objects.new("l", d)
    bpy.context.collection.objects.link(o)
    o.location = loc
    o.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat('-Z', 'Y').to_euler()

area((4, -6, 5), 430, 5, (1, 0.93, 0.84))
area((-5, -4, 1), 110, 6, (0.72, 0.84, 1))
area((-4, 5, 4), 750, 3, (0.62, 0.78, 1))
area((5, 5, -1), 420, 3, (1, 0.72, 0.5))
scene.world = bpy.data.worlds.new("w")
scene.world.use_nodes = True
scene.world.node_tree.nodes["Background"].inputs[0].default_value = (0.75, 0.8, 1, 1)
scene.world.node_tree.nodes["Background"].inputs[1].default_value = 0.28

ARM0, ARM1 = arms[0].rotation_euler.copy(), arms[1].rotation_euler.copy()

def pose(t, talk, has_mail, blink):
    w = 2 * math.pi * t
    say = abs(math.sin(w * 3)) if talk else 0.06
    root.location.z = math.sin(w) * 0.06
    root.rotation_euler = (0, math.sin(w) * 0.025, math.sin(w) * 0.16)
    sq = 1 + math.sin(w * 2) * 0.012
    root.scale = (2 - sq, 2 - sq, sq)
    head.rotation_euler = (0.04 - math.sin(w) * 0.03 + say * 0.05, math.sin(w + 0.5) * 0.04, math.sin(w + 1) * 0.13)
    mouth.scale = (1 + say * 0.14, 1, 0.45 + say * 1.45)
    tongue.location.z = -0.09 - say * 0.04
    teeth.location.z = 0.11 + say * 0.03
    for look, lid in eyes:
        look.location.x = math.sin(w) * 0.07
        look.location.z = math.cos(w) * 0.035
        lid.rotation_euler = (math.radians(-104 + blink * 104), 0, 0)
    for b in brows:
        b.location.z = 0.74 + say * 0.05
    arms[0].rotation_euler = (0.2, ARM0[1] + math.sin(w + 0.6) * 0.14, 0)
    if has_mail:
        arms[1].rotation_euler = (0.1, -2.25 + math.sin(w * 2) * 0.08, 0)
    else:
        arms[1].rotation_euler = (0.2, ARM1[1] - math.sin(w) * 0.14, 0)
    for p in mail_parts:
        p.hide_render = not has_mail

def render(path, t, talk=False, has_mail=False, blink=0):
    pose(t, talk, has_mail, blink)
    bpy.context.view_layer.update()
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)

if MODE == 'preview':
    render(f"{OUT}/talk.png", 0.09, talk=True)
    render(f"{OUT}/mail.png", 0.35, talk=True, has_mail=True)
else:
    N = 24
    for i in range(N):
        t = i / N
        b = {21: 0.5, 22: 1, 23: 0.5}.get(i, 0)
        render(f"{OUT}/idle_{i:02d}.png", t, blink=b)
        render(f"{OUT}/talk_{i:02d}.png", t, talk=True)
        render(f"{OUT}/mail_{i:02d}.png", t, talk=True, has_mail=True)
