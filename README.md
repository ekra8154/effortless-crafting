# Reach Crafting

**Reach Crafting** is a high-performance, client-side Fabric mod for Minecraft 1.21.11 that eliminates the friction of survival crafting. It treats nearby storage as a seamless extension of your own inventory, allowing you to craft complex, high-volume recipes directly from your chests without the "inventory shuffle."

Designed for vanilla-compatible servers, Reach Crafting performs only actions that a standard client could—just much faster and with perfect mathematical precision.

---

## Key Features

### Nearby Container Integration
Stop running back and forth to your chests. Reach Crafting automatically scans nearby containers (Chests, Barrels, Shulker Boxes, Ender Chests) and pulls exactly what you need to complete your craft.
* **Smart Discovery**: Scans within your block interaction range.
* **Automatic Withdrawal**: Fetches ingredients in a single, fluid motion.
* **Lag Resistant**: Built with a robust state machine to handle server latency and container synchronization.

### Smart Redistribution Engine
The heart of the mod is an atomic planning engine that re-balances your crafting grid on the fly.
* **Multi-Material Support**: Crafting a recipe with mixed ingredients (like different types of wood for chests)? The mod intelligently partitions your available materials into the grid.
* **Batch Expansion**: Already have 20 items on the table but need 64? The mod calculates the "top-up" requirements and fills the grid to your target count.
* **Material Prioritization**: Choose whether to use your inventory first or pull from nearby containers to keep your pockets clean.

### Intelligent Family Logic
Reach Crafting understands "Recipe Families" (Concrete, Wool, Glass, Wood, etc.).
* **Family-Aware Indicators**: The recipe book shows a "Nearby Craftable" dot if *any* variant of a family is available.
* **Variant Menu Integration**: Long-click a recipe to see specific craftability indicators for every color and variant.
* **Locked Expansions**: If you have a specific variant on the table (e.g., Yellow Concrete), expanding the craft via the generic recipe button will automatically lock to your current color.

### Vanilla Compatible & Safe
* **Client-Side Only**: No server-side mod required.
* **Legal Movements**: Only performs standard slot clicks and container interactions.
* **Anti-Desync**: Aggressive cursor sanitation and grid clearing ensure you never get items "stuck" to your mouse.

---

## Usage

| Action | Result |
| :--- | :--- |
| **Left Click** | Craft a single item using inventory and nearby materials. |
| **Shift + Click** | Craft as much as possible and move the results to your inventory. |
| **Ctrl + Click** | **Smart Batch**: Fills the crafting grid to the next stack or requested count, redistributing existing materials for maximum efficiency. |
| **Ctrl + Scroll** | **Adjust Count**: Rapidly increase or decrease the target count for batch crafting directly from the recipe book. |
| **Right Click** | Opens the Variant Menu with per-variant craftability indicators. |

---

## Configuration & Policy Logic

Reach Crafting is highly customizable, and its planning engine intelligently combines your settings to match your workflow:

### Setting Interactions
* **Prefer Inventory + Count Preference**: 
    * The mod first identifies all usable items in your player inventory. 
    * It applies your `Count Preference` (Lowest/Highest Total) **within** those inventory items first. 
    * If more materials are needed, it then pulls from nearby containers, again applying your `Count Preference` to the discovered chest contents.
    * *Example*: With `Low Counts` enabled, the mod will use your scarcest inventory wood before touching your large stockpiles in nearby chests.
* **Redistribute on Expansion**: 
    * When enabled, the mod treats batch expansions as atomic operations, clearing and re-balancing the grid to ensure the fastest possible craft.
    * When disabled, the mod performs a "Top-Up," preserving your existing grid layout and variant perfectly while only adding what is missing.
* **Family-Aware Discovery**: 
    * When scanning nearby chests, the mod automatically includes materials for all variants in a family (e.g., discovering all dyes when clicking any concrete color). This ensures that "Locked Expansions" always have the data they need to proceed.

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

This project is licensed under **LGPL-3.0-or-later**. It draws inspiration and certain implementation patterns from the following excellent projects:
* [OneClickCrafting](https://github.com/BreadMoirai/OneClickCrafting) by BreadMoirai (MIT)
* [stack-to-nearby-chests](https://github.com/xiaocihua/stack-to-nearby-chests) by xiaocihua (LGPL-3.0)



TODO:

settings / more to make caching actually faster

clear grid ui button (hold ctrl makes pulled items return?) otherwise eject into inventory (try to remember slots - maybe do this after adding the eject setting mentioned above)

shift craft does not rewrite grid if changing recipes
 - allow hover scrolling over new item to start a new queue

should add setting to switch between append mode and set mode

normal clicking cycling items that should be craftable should craft them

up arrow to restore searches instead of auto loaded in

remove option to turn off hold and release style - its just better

on release, input number count should stay there, and allow the user to decrease or increase it as needed (or change it to a different recipe)

if ctrl or shift is released but spacebar is still held the searchbox doesn't refocus until spacebar is released? on the temporary defocus thing

KNOWN BUGS:
- queuing a new item when one is in the grid ejects the first and pulls the second but both end up in the inventory instead of the second being in the grid

- chests open to return items somehow even if nothing is or needs to be returned

- make sure cache / search excludes out of range and blacklist