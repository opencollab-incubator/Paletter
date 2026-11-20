# Test data from CloudburstMC/Data

The files in the version folders are copies of files of [CloudburstMC/Data](https://github.com/CloudburstMC/Data),
unchanged, licensed under the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
Copyright the CloudburstMC/Data contributors. They are third party data, not Paletter's own captures: they serve only as the
second opinion `CloudburstDataTest` compares the compiled palette against.

| Version | Commit |
|---|---|
| 26.30 | `CloudburstMC/Data@fb969c547236d87a17181941cd585a0eb18f7ceb` (the commit `data/behaviour/REPORT.md` lists; its palette equals that of `4634cd1`, "v1001") |
| 26.40 | `CloudburstMC/Data@619483eb88140f46b8933506c6263861c0d8fa43` ("v2168") |
| 26.50 | `CloudburstMC/Data@a8a4341d7763d6eb8547cff3ca46b4153d60163d` ("26.50") |
| 26.60 | `CloudburstMC/Data@0021592b1a4253528bad1e1d8b537e306e097557` (the commit `data/behaviour/REPORT.md` lists; the palette commit of cb/data's history is `293c848`, "26.60") |

The commits `data/behaviour/REPORT.md` lists for 26.40 and 26.50 (`7046791`, `88a2436`) hold the palette of the following
version, so these two are the palette commits of cb/data's own history. Files: `block_palette.nbt`, `runtime_item_states.json`,
`item_components.nbt`, `creative_items.json` and `data_driven_blocks.nbt` (the commits of 26.30, 26.40 and 26.50 have none).
To refresh one: `git show <commit>:<file>` from a clone of CloudburstMC/Data, written into the folder.
