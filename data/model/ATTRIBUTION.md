# Attribution

`components.json` in this directory (and text derived from it: descriptions, field documentation, value lists and
format version notes in `coverage.md` and in generated sources) is derived from the
**Minecraft Creator documentation** by Microsoft:

- Source: https://github.com/MicrosoftDocs/minecraft-creator, `creator/Reference/Content/` (item and block reference),
  at the commit recorded in `components.json` (`commit`).
- License of the documentation content: **Creative Commons Attribution 4.0 International (CC BY 4.0)**,
  https://creativecommons.org/licenses/by/4.0/ (the repository's `LICENSE`). Code samples in that repository are under
  the MIT license (`LICENSE-CODE`); none are copied here.
- Changes: the Markdown pages were parsed by `DocsParser` (the `docs` command) into a machine-readable model: tables restructured
  into per-field records, types normalized, prose reduced to value lists, ranges and format versions. Corrections where
  the docs disagree with Bedrock Dedicated Server are not applied to `components.json`; they live in `fixtures.json`
  (`errata`) with a reason each.
- No endorsement by Microsoft or Mojang is implied. "Minecraft" is a trademark of Mojang Synergies AB.

Mojang's `bedrock-samples` JSON schemas are not used as an input (all rights reserved).

The list of Bedrock Dedicated Server release builds that `builds list|check` walks (recorded in
`data/bds/builds.json`) comes from **Bedrock-OSS/BDS-Versions**:

- Source: https://github.com/Bedrock-OSS/BDS-Versions, `versions.json`, at the commit recorded in `data/bds/builds.json`
  (`listCommit`, `listDate`). The repository states no license; only the build numbers are used, never its download
  links: the servers themselves are downloaded from minecraft.net.

`mappings.json`, `bds-feedback.json` and `bds-keys.json` are generated from Bedrock Dedicated Server runs of the
generated add-on; `fixtures.json` and `api.json` are written by hand.
