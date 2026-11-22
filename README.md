# Humans

A Paper 26.3 plugin that adds AI "humans": player-like people who live in your survival world.

- They chop, mine, craft, build houses and farms, and raise animals.
- They make plans, form villages, start families, chat with you and each other, and react to what you do to them.

Players don't install anything. Faces come from a small resource pack that the plugin builds and serves itself.

## How it works

Three layers.

1. **Reflexes (Java, every tick).** Instant reactions:
   - swim to shore, eat when hungry, flee creepers
   - put out fires, notice being trapped, underground or burning
2. **Decisions (Gemini Decision 1, every ~30s).** Java lists what a human could do right now (`OptionGenerator`). Decision 1 picks one per human, plus their mood and whether they talk. All nearby humans share one batched call.
3. **Planner (any OpenAI-compatible text model, DeepSeek by default).** For bigger goals like "build a village", "get iron" or "start a family", the planner gets a snapshot of the world and returns a JSON plan of **skills**. Java runs the plan step by step (`plan/`) and reports each result back. The planner then adapts: if the hoe is missing, it crafts one first; if a give failed, it retries with the correct target.
   - Goals come from players in chat, or the humans pick them for their own projects.
   - If the text model fails, it falls back to Gemini Flash-Lite.

### Skills the planner can use

- **Moving:** `go_to`, `follow`, `explore`, `go_outside`, `stand_at`, `wait`
- **Gathering:** `chop_trees`, `mine`, `strip_mine` (digs a staircase down, then tunnels for ores), `collect_items`, `gather_plants`
- **Making:** `craft`, `smelt`, `eat`, `sleep`, `place_bed`
- **Building:** `build`
  - templates: houses with flat or peaked roofs, farms, animal pens, walls, paths
  - `custom`: the planner designs its own layout layer by layer
- **Animals and food:** `farm`, `breed_animals`, `hunt`, `fight`
- **People and items:** `give`, `look_in_chest`, `take_from_chest` (needs permission), `put_in_chest`, `ask`, `say`
- **Village and family:** `set_village`, `have_baby`, `dance`, `pet_animal`

### Everything else

- **Bodies:** a vanilla `Mannequin` entity (player model). Faces are skin textures from the plugin's own resource pack (10 looks × 9 expressions), and each expression change plays a sound.
- **Chat:** Decision 1 reads each message: intent, how it changes trust, mood, and what kind of reply to give.
  - Simple replies ("ok", "thanks", "hi") are ready-made lines. Conversation goes to the text model.
  - Simple requests ("follow me", "dance", "get out of the hole") start immediately.
  - Bigger jobs become planner goals. "Everyone…" makes the humans work together.
- **Respect for players:** humans never break, build or pick up items in players' bases. That covers 9 blocks around players' chests, beds, furnaces and signs, plus any area marked with `/humans protect`. They don't hunt animals there, never attack players, and ask before taking from chests.
- **Families:** two well-fed adults with 6+ food items can have a baby. The baby mixes its parents' looks and personality and grows from half size to adult over about 20 minutes.
- **Budget:** daily and per-minute caps keep the Gemini free tier safe. Humans with no player nearby use a free built-in fallback brain.

## Build

```
gradle build      # JDK 25, Gradle 9.x -> build/libs/humans-0.1.0.jar
```

1. Copy the jar into `plugins/`.
2. Put the API keys in files and point `gemini.api_key_file` and `text.api_key_file` at them in `config.yml`.

Never put keys in the config or in this repo.

## Commands (`/humans`, alias `/hu`, permission `humans.admin`)

- **Humans:** `spawn [name|count]`, `spawnat <x> <z> [n]`, `list`, `info <name>`, `remove <name>`, `home <name>`, `tp <name>`, `give <name> <item> [n]`, `think <name>`
- **Goals:** `goal <name> <text>`, `plan <name>`, `cancel <name>`
- **Protected areas:** `protect <radius> [name]`, `unprotect`, `zones`
- **Admin and debugging:** `budget`, `text`, `debug`, `awake`, `probe <name>`, `pack`, `reloadkey`

In game:
- Talk in normal chat and use a human's name, or "everyone". Ask for anything from "follow me" to "build a village here with a farm".
- Right-click a human for their info card.
- Sneak + right-click to give them the item in your hand.
