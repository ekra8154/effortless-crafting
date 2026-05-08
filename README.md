# Effortless Crafting

**Effortless Crafting** is a client-side Fabric mod for Minecraft 1.21.11 that lets you craft from your inventory and nearby containers without constant inventory shuffling.

It is designed for vanilla-compatible play: the mod only uses normal container interactions and recipe placement that a standard client can already perform.

---

## Key Features

### Nearby Container Integration
Stop running back and forth to your chests. Effortless Crafting scans nearby containers such as chests, barrels, shulker boxes, ender chests, and other supported storage blocks to help complete the current craft.
* **Smart Discovery**: Scans within your block interaction range.
* **Automatic Withdrawal**: Pulls only the ingredients needed for the current craft.
* **Latency Aware**: Uses a session-based workflow that accounts for container opens, restores, and resuming the original crafting screen.

### Smart Craft Expansion
Effortless Crafting can treat the crafting grid as a working plan instead of a one-click action.
* **Batch Expansion**: Grow an existing craft toward the next stack or requested count.
* **Variant Awareness**: Understands recipe families such as wood, wool, glass, and concrete variants.
* **Inventory Preference**: Can prioritize materials already in your inventory before pulling from nearby storage.

### Recipe Book Feedback
The recipe book can surface more than vanilla craftability.
* **Nearby Craftable Indicators**: Shows when cached nearby storage appears to satisfy missing ingredients.
* **Queued Count Indicators**: Displays queued recipe counts while modifier-based crafting is active.
* **Variant Overlay Support**: Works with recipe families and per-variant selection.

### Vanilla-Friendly Operation
* **Client-Side Only**: No server-side mod required.
* **Vanilla UI Driven**: Uses standard slot clicks, recipe placement, and container interactions.
* **Configurable Workflow**: Includes options for inventory preference, search behavior, nearby-container usage, filtering, and output handling.

---

## Usage

| Action | Result |
| :--- | :--- |
| **Left Click** | Craft a single item using inventory and nearby materials. |
| **Shift + Click** | Craft as much as possible and move the results to your inventory. |
| **Ctrl + Click** | **Smart Batch**: fills the crafting grid toward the next stack or requested count, redistributing materials when needed. |
| **Shift + Scroll** | **Scroll to Pull**: pulls crafted output to the cursor or directly into a hovered inventory slot, depending on your setting. |
| **Right Click** | Opens the variant menu with per-variant craftability indicators. |

---

## Configuration

Effortless Crafting includes options for:
* preferring inventory over nearby containers
* choosing high-count vs low-count variant usage
* caching nearby container contents for faster recipe checks
* remembering recipe-book search behavior
* restoring inventory layout after crafting
* in-world container filtering and optional wireframe outlines

If Mod Menu and Cloth Config are installed, the mod exposes an in-game config screen.

---

## Development

Building the project locally:

```powershell
# Run the client
.\gradlew.bat runClient

# Build the mod JAR
.\gradlew.bat build
```

---

## Credits & License

This project is licensed under **LGPL-3.0-or-later**. It draws inspiration and certain implementation patterns from:
* [stack-to-nearby-chests](https://github.com/xiaocihua/stack-to-nearby-chests) by xiaocihua (LGPL-3.0)


TODO: 

setting: toggle autocraft off after bulk craft

BUGS:

cant do iron bars with only 1 inventory slot