# Attribution

`components.json` in this directory (and text derived from it: descriptions, field documentation, value lists and
format version notes in `coverage.md` and in generated sources) is derived from the
**Minecraft Creator documentation** by Microsoft:

- Source: https://github.com/MicrosoftDocs/minecraft-creator, `creator/Reference/Content/` (item and block reference),
  at the commit recorded in `components.json` (`commit`).
- License of the documentation content: **Creative Commons Attribution 4.0 International (CC BY 4.0)**,
  https://creativecommons.org/licenses/by/4.0/ (the repository's `LICENSE`). Code samples in that repository are under
  the MIT license (`LICENSE-CODE`); none are copied here.
- Changes: the Markdown pages were parsed by `spec` (`DocsParser`) into a machine-readable model: tables restructured
  into per-field records, types normalized, prose reduced to value lists, ranges and format versions. Corrections where
  the docs disagree with Bedrock Dedicated Server are not applied to `components.json`; they live in `fixtures.json`
  (`errata`) with a reason each.
- No endorsement by Microsoft or Mojang is implied. "Minecraft" is a trademark of Mojang Synergies AB.

Mojang's `bedrock-samples` JSON schemas are not used as an input (all rights reserved).

Everything else here (`mappings.json`, `bds-feedback.json`, `captures/`) is generated from Bedrock Dedicated Server
runs of the generated add-on; `fixtures.json` and `transforms.json` are written by hand.
