# Captured BDS data

Captures of Bedrock Dedicated Server that keep the evidence they were measured from. Written by the tool named in each
line (never edit by hand); running it again replaces its line.

| BDS | Directory | What | Command |
|-----|-----------|------|---------|
| 1.26.63.1 (Build ID 53340801) | `data/bds/1.26.63.1/behaviour-probe/` | What BDS does with a custom block that sets no component (friction, explosion resistance, light, collision, fire) and the collision boxes of every door in every state, measured by experiment with data/addons/probe | `./gradlew :tools:run --args="behaviour-probe --bds 1.26.63.1"` |
