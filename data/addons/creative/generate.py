"""Generates the creative order probe add-on (behavior pack only): python generate.py capture/creative-addon

Custom items and blocks with every kind of menu_category placement, to see where BDS puts them in the creative
inventory (capture/CREATIVE-ORDER.md). File names are deliberately unrelated to the identifiers (and their order differs
from identifier order), and several namespaces are used, so definition order, file order and identifier order can be
told apart."""
import json, os, shutil, sys

OUT = sys.argv[1]
FV = "1.26.60"
BP = os.path.join(OUT, "behavior_pack")
BP_UUID, BP_MOD = "a1e5c0de-0000-4000-8000-00000000d001", "a1e5c0de-0000-4000-8000-00000000d002"

if os.path.isdir(BP):
    shutil.rmtree(BP)


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def menu(category=None, group=None, hidden=None):
    m = {}
    if category is not None:
        m["category"] = category
    if group is not None:
        m["group"] = group
    if hidden is not None:
        m["is_hidden_in_commands"] = hidden
    return m


SWORD = "minecraft:itemGroup.name.sword"
PLANKS = "minecraft:itemGroup.name.planks"

# (file name, identifier, menu_category or None, components)
ITEMS = [
    ("f17", "exp:loose_m", menu("items"), {}),
    ("f03", "zzz:loose_a", menu("items"), {}),
    ("f11", "aaa:loose_z", menu("items"), {}),
    ("f02", "exp:no_menu", None, {}),
    ("f14", "exp:cat_none", menu("none"), {}),
    ("f05", "exp:hidden_items", menu("items", hidden=True), {}),
    ("f09", "exp:sword_c", menu("equipment", SWORD), {}),
    ("f01", "exp:sword_a", menu("equipment", SWORD), {}),
    ("f12", "zzz:sword_b", menu("equipment", SWORD), {}),
    ("f06", "aaa:sword_d", menu("equipment", SWORD), {}),
    ("f21", "exp:sword_wrongcat", menu("items", SWORD), {}),
    ("f08", "exp:g1_b", menu("equipment", "exp:itemGroup.g1"), {}),
    ("f13", "exp:g1_a", menu("equipment", "exp:itemGroup.g1"), {}),
    ("f04", "exp:g0_x", menu("equipment", "exp:itemGroup.g0"), {}),
    ("f16", "aaa:g2_x", menu("equipment", "exp:itemGroup.g2"), {}),
    ("f22", "exp:g1_other", menu("nature", "exp:itemGroup.g1"), {}),
    ("f10", "exp:nature_loose", menu("nature"), {}),
    ("f18", "exp:equip_loose", menu("equipment"), {}),
    ("f19", "exp:construction_loose", menu("construction"), {}),
    ("f15", "exp:block_with_item", menu("items"), {}),
    ("f07", "exp:placer_item", menu("items"), {"minecraft:block_placer": {"block": "exp:block_placed", "replace_block_item": True}}),
    ("f24", "exp:group_only", menu(group="exp:itemGroup.g4"), {}),
]

BLOCKS = [
    ("b07", "exp:block_b", menu("construction")),
    ("b02", "exp:block_a", menu("construction")),
    ("b11", "zzz:block_c", menu("construction")),
    ("b05", "aaa:block_d", menu("construction")),
    ("b09", "exp:block_sword", menu("equipment", SWORD)),
    ("b01", "exp:block_g1", menu("equipment", "exp:itemGroup.g1")),
    ("b12", "exp:block_g3", menu("nature", "exp:itemGroup.g3")),
    ("b03", "exp:block_none", menu("none")),
    ("b10", "exp:block_nomenu", None),
    ("b04", "exp:block_items", menu("items")),
    ("b08", "exp:block_with_item", menu("construction")),
    ("b06", "exp:block_placed", menu("construction")),
    ("b13", "exp:block_planks", menu("construction", PLANKS)),
    ("b14", "exp:block_hidden", menu("construction", hidden=True)),
]

for file, identifier, m, components in ITEMS:
    description = {"identifier": identifier}
    if m is not None:
        description["menu_category"] = m
    # an item needs at least one component, or BDS registers it without its description
    components = components or {"minecraft:max_stack_size": 64}
    write(os.path.join(BP, "items", file + ".json"),
          {"format_version": FV, "minecraft:item": {"description": description, "components": components}})
# BDS numbers custom blocks in descending file name order; Paletter (no files) by descending identifier. Name the
# block files so both agree: b<rank in identifier order>. (An earlier version used file names unrelated to the
# identifiers: captures/1.26.63.1/creative-probe-file-order, the evidence for the file name order.)
RANK = {identifier: i for i, identifier in enumerate(sorted(b[1] for b in BLOCKS))}
BLOCKS = [(f"b{RANK[identifier] + 1:02d}", identifier, m) for _, identifier, m in BLOCKS]
for file, identifier, m in BLOCKS:
    description = {"identifier": identifier}
    if m is not None:
        description["menu_category"] = m
    write(os.path.join(BP, "blocks", file + ".json"),
          {"format_version": FV, "minecraft:block": {"description": description, "components": {}}})
write(os.path.join(BP, "manifest.json"), {
    "format_version": 2,
    "header": {"name": "Paletter creative order probe", "description": "Custom items and blocks with every menu_category placement",
               "uuid": BP_UUID, "version": [1, 0, 0], "min_engine_version": [1, 26, 60]},
    "modules": [{"type": "data", "uuid": BP_MOD, "version": [1, 0, 0]}]})
print(f"{len(ITEMS)} items, {len(BLOCKS)} blocks -> {OUT}")
