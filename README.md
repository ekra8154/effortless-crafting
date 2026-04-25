# Reach Crafting

Client-side Fabric mod for Minecraft 1.21.11 focused on reducing survival crafting friction on vanilla-compatible servers.

## Goal

The long-term goal is to make nearby storage feel like an extension of the player's hands without requiring any server-side mod.

Planned feature areas:

1. Craft from nearby containers:
   Click a recipe, pull the exact missing ingredients from nearby containers using normal client actions, then complete the craft through the vanilla UI.
2. Rebuild serialized storage layouts:
   Record a chest layout, then later reproduce that layout in survival by gathering, crafting, and placing the required items back into a new chest.


### Part 1: Craft from nearby containers

- `OneClickCrafting` proves the recipe-book click interception and automated crafting side is viable.
- `stack-to-nearby-chests` proves nearby-container discovery, opening, and legal inventory movement on a vanilla server is viable.
- The hard part will be orchestration: detecting what the recipe still needs, withdrawing only those ingredients from nearby containers, preserving sync with the current screen handler, and recovering cleanly from latency, full inventory, or unavailable items.

Current expectation: good candidate for the first real milestone.

### Part 2: Rebuild serialized double chests

This will hopefully also be possible but definitely much harder. 

- Serializing a chest in an orientation-agnostic way is straightforward.
- Reproducing it in survival is where complexity jumps: recipe dependency planning, batching crafts, managing free inventory space, handling nearby storage, and placing the exact target counts into exact slots.
- If the target chest contains special NBT-heavy items, mixed-damage tools, or shulkers with contents, scope grows again.

Current expectation: absolutely feasible as a later feature, but it should come after Part 1 is stable.

## Roadmap

1. MVP: while a crafting table or 2x2 inventory crafting screen is open, click a recipe and auto-fetch only the missing ingredients from nearby containers.
2. Auto-complete the craft and move the result to inventory.
3. Add batching, repeat craft, and better failure messaging.
4. Add chest layout serialization.
5. Add planned reconstruction of saved layouts.

## Vanilla compatibility constraints

This project is intended to stay client-side only.

That means the mod should only perform actions a vanilla client could already perform:

- open reachable containers
- click slots through normal screen handlers
- move items between inventory and containers
- craft through the normal inventory or crafting-table UI

If we keep the implementation at that level, using it on vanilla servers should be realistic.

## References

- `reference/OneClickCrafting`
- `reference/stack-to-nearby-chests`

These are reference projects for behavior and implementation ideas, not bundled dependencies.

## Credits

This project draws implementation ideas from:

- `OneClickCrafting` by BreadMoirai, licensed under MIT
- `stack-to-nearby-chests` by xiaocihua, licensed under LGPL-3.0-or-later

If specific code is copied or adapted from either project, the relevant upstream copyright
and license notices should remain preserved in this repository.

## Development

Run the client with:

```powershell
.\gradlew.bat runClient
```

## License

This project is licensed under `LGPL-3.0-or-later`.

That is the safest default because one of the reference projects, `stack-to-nearby-chests`, is licensed under `LGPL-3.0-or-later`. If we directly adapt logic from it, this project needs a compatible license. If the mod later becomes a clean-room reimplementation with no copied LGPL-covered code, the licensing choice can be revisited.

