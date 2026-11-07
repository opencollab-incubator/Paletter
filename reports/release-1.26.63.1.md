# Paletter data verification: BDS 1.26.63.1

- **Date**: 2026-11-07
- **Mode**: verify only (nothing written)
- **BDS**: 1.26.63.1 win (https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.26.63.1.zip)
- **Work directory**: `C:\Users\onebe\.gradle\caches\paletter\bds\runs\win-1.26.63.1` (logs, captures)
- **Server**: `C:\Users\onebe\.gradle\caches\paletter\bds\servers\win-1.26.63.1` (removed after the run)
- **Protocol**: 2225 (RakNet pong: 1.26.63)
- **Transport**: RakNet
- **Bundled version**: 26.60 (existing, same protocol)
- **Result**: IDENTICAL

## Checks

| Status | Check | Detail |
|---|---|---|
| PASS | Script API dump | BDS reports 1.26.63.1; 1623 items, 1480 blocks, 77 item tags |
| PASS | vanilla capture | hashed block ids; protocol 2225 |
| PASS | Block hashes (vanilla) | all 1065 creative block ids are FNV-1a 32 hashes of palette states |
| PASS | vanilla capture (sequential ids) | sequential block ids; protocol 2225 |
| PASS | add-on capture | hashed block ids; protocol 2225 |
| PASS | Block hashes (add-on) | all 2256 creative block ids are FNV-1a 32 hashes of palette states |
| INFO | Experiments echoed by BDS | upcoming_creator_features=true, gametest=true, experimental_creator_cameras=true, y_2026_drop_4=true |
| PASS | add-on capture (sequential ids) | sequential block ids; protocol 2225 |
| PASS | Block numbering | reference palette 26.60: 23960 states in FNV-1 64 name order |
| PASS | Block numbering | vanilla: 1019 sequential creative block ids match the FNV-1 64 name sort of 23960 states |
| PASS | Block numbering | add-on: 1082 sequential creative block ids match the FNV-1 64 name sort of 26346 states |
| PASS | Block numbering | add-on: 66 custom block ids are 10000+ in descending identifier order |
| PASS | Block numbering | add-on: 65 custom block items have runtime id 255 - block_id |
| PASS | Script API vs baseline 1.26.63.1 | identical |
| PASS | Network data vs bundled 26.60 | identical (items, runtime ids, components, data driven blocks, creative layout) |
| PASS | Item tags and block defaults vs bundled 26.60 | identical |
| PASS | Add-on content log | 4 known rejections/warnings, unchanged |
| PASS | Component shapes | add-on components vs bds-1.26.63.1-addon: identical |
| PASS | Component shapes | vanilla components vs bundled 26.60: identical |
| PASS | Spec coverage | every captured component shape is in codegen/spec/inferred.json |
| PASS | Add-on capture vs codegen/captures | `codegen\captures\bds-1.26.63.1-addon` has the same NBT |

