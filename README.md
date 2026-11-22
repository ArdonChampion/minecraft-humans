# Humans

A Paper 26.3 plugin that adds AI "humans": player-like people who live in your survival world. They chop, mine, craft, build houses, pet and breed animals, pick flowers, watch sunsets, chat with each other and with you, and react to what you do to them.

Players don't install anything. Faces come from a small resource pack that the plugin builds and serves itself.

## How it works

- **Body:** a vanilla `Mannequin` entity (player model). Faces are skin textures from the plugin's own resource pack (10 looks × 9 expressions), and each expression change plays a sound.
- **Brain:** Java lists what a human could do right now (`OptionGenerator`). **Gemini Decision 1** picks one option per human, plus their mood and whether they want to talk. All nearby humans share one batched call. Tasks are carried out by deterministic Java (`tasks/`).
- **Chat:** Decision 1 reads each message: intent, how it changes trust, mood, and what kind of reply to give. Simple replies are ready-made lines. Only real conversation goes to **Gemini Flash-Lite**.
- **Requests** ("follow me", "dance", "get out of the hole", "put out the fire"…) are carried out directly. Humans agree most of the time unless they dislike you.
- **Awareness** (`Awareness.java`) detects being trapped in a hole or pit, fire nearby, and their house burning, then reacts.
- **Budget:** daily and per-minute caps keep usage inside the free tier. Humans with no player nearby use a free built-in fallback brain.

## Build

```
gradle build      # JDK 25, Gradle 9.x -> build/libs/humans-0.1.0.jar
```

Copy the jar into `plugins/`. Put the Gemini API key in the file named by `gemini.api_key_file` in `config.yml`. Never put the key in the config or in this repo.

## Commands (`/humans`, alias `/hu`, permission `humans.admin`)

`spawn [name|count]`, `spawnat <x> <z> [n]`, `list`, `info <name>`, `remove <name>`, `home <name>`, `tp <name>`, `give <name> <item> [n]`, `think <name>`, `budget`, `debug`, `awake`, `probe <name>`, `pack`, `reloadkey`

In game:
- Talk in normal chat and use a human's name.
- Right-click a human for their info card.
- Sneak + right-click to give them the item in your hand.
