"""Generates the large creative order probe add-on: 700 blocks and 40 items with random identifiers and menu categories
(fixed seed; block files named by identifier rank, items randomly), so the data driven block map of BDS grows past 512 buckets:
python generate_large.py <out dir>. Captured in captures/1.26.63.1/creative-large (see CREATIVE-ORDER.md)."""
import json, os, random, shutil, sys

OUT = sys.argv[1]
BP = os.path.join(OUT, "behavior_pack")
if os.path.isdir(BP):
    shutil.rmtree(BP)
random.seed(7)
os.makedirs(BP + '/blocks')
os.makedirs(BP + '/items')
names = [f"{random.choice(['aaa', 'exp', 'qq', 'zzz'])}:b{random.randint(0, 10**6)}_{i}" for i in range(700)]
files = list(range(700))
random.shuffle(files)  # unused since block files are named by identifier rank, kept so the random sequence stays the same
rank = {name: i for i, name in enumerate(sorted(names))}
cats = ['construction', 'nature', 'equipment', 'items']
for i, n in enumerate(names):
    m = {"category": random.choice(cats)}
    r = random.random()
    if r < 0.2:
        m["group"] = "minecraft:itemGroup.name.sword"
    elif r < 0.4:
        m["group"] = "big:itemGroup.g%d" % random.randint(0, 5)
    json.dump({"format_version": "1.26.60", "minecraft:block": {"description": {"identifier": n, "menu_category": m}, "components": {}}},
              open(f"{BP}/blocks/x{rank[n]:04d}.json", "w"))
for i in range(40):
    m = {"category": random.choice(cats)}
    if random.random() < 0.5:
        m["group"] = "big:itemGroup.g%d" % random.randint(0, 5)
    json.dump({"format_version": "1.26.60", "minecraft:item": {"description": {"identifier": f"big:i{random.randint(0, 999)}_{i}", "menu_category": m},
                                                               "components": {"minecraft:max_stack_size": 64}}},
              open(f"{BP}/items/y{random.randint(0, 99999):05d}_{i}.json", "w"))
json.dump({"format_version": 2, "header": {"name": "big", "description": "big", "uuid": "a1e5c0de-0000-4000-8000-00000000e001",
                                           "version": [1, 0, 0], "min_engine_version": [1, 26, 60]},
           "modules": [{"type": "data", "uuid": "a1e5c0de-0000-4000-8000-00000000e002", "version": [1, 0, 0]}]},
          open(BP + '/manifest.json', 'w'))
