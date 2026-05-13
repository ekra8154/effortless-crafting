# Effortless Crafting

**Effortless Crafting** is a powerful client-side Fabric mod for Minecraft 1.21.11 that drastically streamlines crafting by pulling items directly from your inventory and nearby containers.

It is designed for strict vanilla-compatible play. The mod operates entirely client-side, using standard container interactions and recipe placement that a vanilla client can already perform—meaning it works seamlessly on vanilla servers!

---

## Keybindings & Controls

The mod introduces several new interactions to make crafting effortless, while ensuring that vanilla **normal clicking and shift-clicking recipes remain entirely untouched**.

| Action | Result |
| :--- | :--- |
| **Ctrl + Hover + Click/Scroll** | **Nearby Craft**: Queue ingredients to pull from nearby containers and inventory. |
| **Shift + Hover + Scroll** | **Inventory Craft**: Queue ingredients to use from your inventory only. |
| **Ctrl + Shift + Click** | **Max Craft**: Crafts as much as will fit into the crafting grid simultaneously. Like a Vanilla shift + click but with nearby containers. |
| **Ctrl + Shift + Click (Bulk Enabled)** | **Max Bulk Craft**: Continuous max crafts until containers and inventory run out or until aborted. |
| **Alt (Held or Toggled)** | **Auto-Craft**: Signals and engages the auto-crafting mode. |
| **Alt + Click Result Slot** | **Enable Bulk Craft**: Temporarily enables bulk craft mode. |
| **Spacebar (Held during queuing or pulling)** | **x16 Multiplier**: Scales your requested craft count or scroll to pull amount by 16 at a time. Works with scrolling wraparound. |
| **Spacebar (Default)** | **Recipe Filter Toggle**: Instantly toggles the recipe book filter between craftable and uncraftable. |
| **Right Click (Revolving Recipe)** | **Open Variant Menu**: Vanilla behavior that allows explicit variant requests regardless of the variant setting. |
| **B (Default Hotkey)** | **Quick Craft**: Auto-opens a nearby 3x3 crafting table if available; otherwise opens the 2x2 inventory grid and auto-focuses the search bar. |
| **ESC** | **Abort**: Immediately cancels any active or queued crafting session early. |

---

## Core Mechanics

### Intelligent Resource Management
* **Variant Resolution**: The mod intelligently resolves recipe variants (e.g., different types of wood or colors of wool) based on your request and what is currently available in storage.
* **Smart Chest Interaction**: Prioritizes pulling from near-empty slots to help keep chests clean and consolidated.
* **Item Return & Inventory Restore**: Remembers your inventory layout and attempts to place items back exactly where they were after a craft is finished.
* **High-Performance Caching**: Caches nearby container contents to significantly speed up same-session crafts and to drive UI indicators showing what can be crafted.

### Auto-Crafting
* **Toggle or Hold Modes**: Configure the mod to auto-craft by pressing (default) or holding Alt to toggle it on/off. Signaled with an arrow in the crafting result slot.
* **Quick Toggle**: Engaging auto-crafting for whatever recipe is currently staged in the grid will autocraft it without needing to use the mouse.
* **Strict Manual Override**: Manual crafts are never automatically crafted unless it is toggled while items are already staged in the grid.

### Bulk Crafting
* **Operation** Hold Alt and click the arrow in the crafting result slot to temporarily enable bulk craft mode, signaled with an orange outline around the arrow. This uncaps the item queuing limit, allowing for quantities greater than 64
* **Nonstackable Crafting** Bulk craft large quantities of nonstackable items or any type of item with repeated autocrafts. This removes all of the vanilla tediousness of crafting dispensers, cakes, or large quantities of other items
* **Dynamic Staging & Ejection**: Calculates available inventory space and stages bulk crafts to execute the maximum possible amount at a time, ejecting outputs to optimize space and increase speeds.
* **Family Switching**: Can seamlessly transition between available variants in a recipe family (like switching from Oak to Jungle planks) when materials run out mid-batch if the setting is enabled. 
* **Bulk Max Craft**: Continuously crafts the requested item until all nearby resources are exhausted or the session is aborted.
* **Safe Disable**: Automatically disables crafting sessions upon completion, manual abort (ESC), or if the game window loses focus, preventing unwanted items from being crafted.

### Scroll to Pull
A specialized form of auto-crafting designed for rapid, controlled output that works perfectly alongside the Spacebar x16 multiplier
* **Shift + Scroll Down (Result slot hovered)**: Pulls the crafted result directly into your inventory (or automatically ejects it if your inventory is full).
* **Shift + Scroll Down (Inventory slot hovered)**: Pulls the crafted result directly into the hovered inventory slot if possible.
* **Shift + Scroll Up**: Appends the crafted result directly to your cursor stack.

---

## Quality of Life Tweaks

### Smart Item Transfer
* **Hotbar Left-to-Right**: Intelligently fills your hotbar from left to right when automatically placing items in-inventory rather than the vanilla right to left, making newly crafted items more accessible. Vanilla style can still be achieved through normal shift clicks.
* **Grow Existing Stacks**: Prioritizes appending to existing item stacks in your inventory before occupying empty slots.
* **Offhand Stacking**: Supports stacking crafted items into your offhand slot and works cleanly for both 2x2 and 3x3 grids. (For 3x3, temporarily swaps the offhand item into the inventory)

### Recipe Book & UI Enhancements
* **Type to Search**: Typing can automatically type immediately into the search bar, only ignoring special keys such as the drop key
* **Search History**: Navigate through your previous recipe book searches with up and down arrows.
* **3x3 Auto-Focus**: Search bar is automatically focused when opening a crafting table.
* **Yield & Queue Indicators**: Shows the exact yield total if all are crafted in that specific stack size.
* **Chat Reporting & Feedback**: Dynamic, localized deficit reports tell you exactly what ingredients are missing, and bulk craft reports summarize all of the crafted items during the session. 

---

## Customization & Filtering

### High Customizability
Effortless Crafting is designed to fit your playstyle. Almost every feature can be tweaked in the mod settings (requires Mod Menu and Cloth Config). You can adjust timings, change auto-craft behavior, or even **disable the mod entirely** with a single toggle. This way, regardless of server rules, capabilities of effortless crafting can be used. 

### Container Filtering System
* **Global Container Types Blacklist** Globally stop certain container types like hoppers or enderchests from having items pulled from them unless a specific in world one explicitly whitelisted. 
* **In-World Blacklist/Whitelist**: Gain complete control over which containers the mod is allowed to interact with. When this setting is enabled, hold ctrl while in a container UI to view and change its black/unset/whitelist status by clicking the dot at the top.
* **Visual Wireframes**: Toggleable in-world wireframes clearly display the status of filtered containers.

---

### Credits & License
This project is licensed under **LGPL-3.0-or-later**. It draws inspiration and certain implementation patterns from:
* [stack-to-nearby-chests](https://github.com/xiaocihua/stack-to-nearby-chests) by xiaocihua (LGPL-3.0)

---