# Effortless Crafting

---

**Effortless Crafting** is a powerful client side crafting overhaul QoL mod that makes crafting faster and easier than ever! It allows crafting complex items in massive quantities from only the raw materials in a matter of seconds, and without even having to put the items in your inventory. 


## Overview: 

- `ctrl` pulls items, allowing crafting with items in nearby chests
- `alt` autocrafts, and can craft all in-between steps to the final product
- Bulk craft quickly crafts huge amounts of items, including unstackable recipes such as dispensers
- Prioritizes giving you the items if they exist already nearby
- Double tap `ctrl` to fetch items from nearby chests without manually searching
- Tap `alt` to autocraft the grid without shift clicking
- `alt + click` a recipe to autocraft it immediately in just one click
- Scroll while holding modifier keys to quickly stage/craft/pull specific amounts
- See exactly what a craft will use before it happens with the queue preview
- Remembers what is in your chests, even after relaunching
- Press `B` to open a nearby crafting grid
- Supports container whitelisting and blacklisting to keep item sorters safe
- Places items in hotbar left to right for easier access
- Autofocus recipe search when typing


To be as server-rule friendly as possible:
- caches items to minimize chest opening actions
- disable nearby chest usage in settings
- adaptive crafting rate when it detects limiting that can also be manually set

Note that automated chest opening (nearby crafting) could get flagged on public servers with anticheat and potentially result in a ban, so please use 'ctrl' crafting with caution, knowing server rules.

<div align="center">
  <img src="https://i.ibb.co/20jFVHVG/Minecraft-26-2-Singleplayer-2026-09-02-20-21-09-3.gif" width="700" alt="Bulk chain crafting an unstackable recipe">
</div>

---

## Quick Start

Most of the mod's power comes from **recipe requests**:

1. Hover a recipe.
2. Hold one or more modifier keys.
3. Scroll to choose how many, then release to send the request.

The three main modifiers are:

- `Shift`: queue a normal inventory-only craft
- `Ctrl`: allow nearby containers to be used
- `Alt`: queue the craft as an autocraft request

If dynamic auto crafting is enabled, 'Alt' works for all requests, and will dynamically decide whether to do an inventory only craft, nearby craft, or chain craft depending on resources based on the queued recipe.

Queue a craft with just one modifier, or all 3 at once! Ctrl + Alt for example performs a nearby autocraft. 

Or Alt + click the crafting result slot to enable bulk mode, and uncap craft limits!

Tapping alt will autocraft whatever is currently in the grid, making it a quick and easy way to move items without needing to click anything.

---

## Modifiers

| Modifier | Main meaning | On click | On scroll |
| :--- | :--- | :--- | :--- |
| `Shift` | Inventory-only recipe request | Elevates request to a max craft (as much as will fit in the grid at once) | Changes the queued amount, and on releasing all held modifiers sends the request. |
| `Ctrl` | Enable nearby containers | Adds 1 to the request with access to nearby containers | Changes the queued amount, and on releasing all held modifiers sends the request with access to nearby chests/barrels etc. |
| `Alt` | Autocraft this request | Instantly crafts 1 if enabled, otherwise adds 1 to the request | Changes the queued amount, and on releasing all held modifiers sends the request with autocraft applied to that request. |

`Spacebar` acts as an `x16` multiplier while queuing or scroll-pulling.  
`Right Click` the queue or `Esc` cancels any queued input before release while a request is being queued.  
`Esc` will immediately abort any crafting session initiated by the mod.  

Combinations of the modifiers stack naturally.

- `Ctrl + Shift + click`: nearby max craft
- `Ctrl + Shift + click` with bulk enabled: repeated nearby max crafts until resources run out or the session is stopped
- `Alt + Shift + click`: inventory-only max craft with autocraft enabled
- `Ctrl + Alt + Shift + click`: nearby max craft with autocraft enabled
- `Ctrl + Alt + scroll`: nearby autocraft request
- `Shift + Alt + scroll`: inventory autocraft request (same as just alt + scroll)

---

## Recipe Indicators

A dot in the corner of each recipe shows at a glance how it can be crafted:

- **Filled dot**: craftable from your inventory alone
- **Plus**: craftable, but needs items from nearby containers
- **Yellow**: a direct craft. **Orange**: a chain craft, which makes the in-between items too
- **Green dot underneath**: copies of the item already sit in a nearby chest, ready to be pulled out instead of crafted
- **While queuing**: the dot describes the whole queued amount, not just one craft, so it changes as you scroll past what your inventory can make

The total above the result slot shows how many the queue will craft, and turns orange when your materials can't make all of it. Turn the dots off with **Craftability Indicators**.

---

## Queue Preview

- While a recipe is queued, the crafting grid shows exactly which item goes in each slot and how many, up to one full batch. The first batch is then placed exactly as shown.
- A yellow dot marks items that will come from nearby containers. An orange dot marks items a chain craft will make along the way.
- The result slot shows how many the craft will actually make, including anything retrieved from nearby chests first.
- The preview is drawn faded in place of the slot's contents, so it works with resource packs that retexture the GUI. Toggle it with **Queue Preview**.

---

## Trailer / Quick Tutorial
<div>
  <iframe src="https://www.youtube-nocookie.com/embed/l7aqAwJ9QH8" 
          title="YouTube video player" 
          style="width: 280px !important; height: 157px !important;" 
          frameborder="0" 
          allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" 
          allowfullscreen>
  </iframe>
</div>

---

**Some Useful Features not Addressed in the Video** 
- Chain Crafting: Form of autocrafting where if enabled, autocrafts every step in between as well to reach the final desired item. Ex: logs-->planks-->chest-->hopper all automatically.
- Retrieval Mode: Used for finding and quickly pulling in existing items from nearby chests, activated by double tapping ctrl or holding it and clicking the craft result slot
- `Right Click` on a revolving recipe opens the Vanilla variant picker, which matters if you want one exact variant instead of letting the mod resolve from the whole recipe family.
- Holding `Spacebar` acts as an `x16` multiplier while queuing or scroll-pulling.  
- `Right Click` or `Esc` cancels any queued input before release while a request is being queued.  
- `Esc` will immediately abort any crafting session initiated by the mod.
- Tap `Alt` to autocraft whatever is currently in the grid  
- Hold `Alt` and click a recipe to instantly craft 1 of it
- `/effortlesscrafting help` explains every feature in short pages right in chat

---

## Pre-existing Vanilla behaviors

- This mod does not modify existing vanilla recipe interactions and hotkeys
- Normal recipe clicks still behave like vanilla.
- Vanilla `Shift + click` remains the familiar max-craft action unless you combine it with the mod's other modifiers.
- Vanilla's restore last craft 'Spacebar' still works
- Vanilla right click on a revolving recipe opens the variant picker.
- That variant picker matters if you want one exact variant instead of letting the mod resolve from the whole recipe family.

---

## Good To Know

- Turning autocraft on will craft whatever is currently in the grid. This makes it possible to request a recipe first, then autocraft it afterward without rebuilding the request.
- Manual recipes are not auto-crafted unless autocraft is turned on while something is already staged in the grid. This allows tapping alt to quickly craft items and moving them into your hotbar.
- `Alt + click` can instantly craft 1 when **Alt Click Instant Craft** is enabled.
- Bulk mode removes the normal queue cap and enables repeated autocrafts and repeated max crafts.
- `Shift + scroll` over the result slot can also be used as a fast **scroll to pull** shortcut for crafted results.

---

## Quality Of Life Tweaks

### Smart Item Transfer

- **Hotbar left-to-right**: Automatically placed crafted items fill the hotbar from left to right instead of vanilla's usual right-to-left behavior to make new items easier to access
- **Offhand stacking**: Crafted items can be stacked directly onto the offhand slot and works for both 2x2 and 3x3 crafting grids.

### Recipe Book And UI Enhancements

- **Type to search**: Typing can jump directly into the recipe book search bar while ignoring keys that should not become text input.
- **Search history**: Use the up and down arrows to cycle through previous recipe book searches.
- **3x3 auto-focus**: The search bar is automatically focused when opening a crafting table.
- **Yield and queue indicators**: The UI can show output totals and queued counts directly on recipes.
- **Chat feedback**: Missing ingredient reports and bulk craft summaries are surfaced in chat.
- **Recipe filter toggle**: Default key `Spacebar` quick toggles the craftable/uncraftable recipe filter when not being used for request scaling.
- **Quick Craft hotkey**: `B` by default opens a nearby 3x3 crafting table when possible, otherwise falls back to the 2x2 inventory grid and focuses search.

---

## Auto-Crafting

- **Hold vs Toggle modes**: By default, autocraft uses hold mode. Holding `Alt` signals autocraft behavior for the current request. If preferred, autocraft can also be configured to use a toggle mode in settings. When autocraft is active, the arrow in the result slot indicates it.
- **Dynamic Auto Craft**: Autocrafts choose where to get items from, so you never need to hold `Ctrl`. They take the simplest way that covers the request: items already in nearby chests (the green dot), then your inventory, then nearby containers, then a chain craft from your inventory, then a chain craft with nearby containers. Crafts without autocraft keep the usual keys, and `Ctrl` still turns nearby containers on. On by default.
- **Quick autocraft from the grid**: If a recipe is already staged, tapping `Alt` can craft it without needing to rebuild the request.
- **Instant craft option**: `Alt + click` can instantly craft 1 of the hovered recipe when **Alt Click Instant Craft** is enabled. If disabled, it behaves like a normal Alt request click instead.
- **Smart placement**: Autocrafted items are placed intelligently, including left-to-right hotbar filling instead of vanilla's usual right-to-left behavior.
- **Offhand stacking**: Autocraft can stack compatible outputs into the offhand slot and works for both 2x2 and 3x3 crafting grids.
- **Manual recipe safeguard**: Recipes placed into the grid manually are never auto-crafted unless autocraft is turned on while that recipe is already staged.

---

## Chain Crafting

Chain crafting extends autocraft by working backward through craftable dependencies when the final recipe is missing intermediate ingredients.

- **Dependency crafting**: If you request an autocraft for something like a comparator but only have logs, redstone, stone, and quartz, the mod can craft planks, sticks, redstone torches, and then the comparator.
- **Ask first or always**: Chain crafting can be disabled, ask for confirmation, or run automatically from the mod settings.
- **Fewer prompts**: With **Only Prompt When Different** (on by default), the chain and retrieve-then-ask prompts are skipped when they would only repeat what the queue preview or the orange chain dot already showed you. They still ask when the mod finds something different, such as chests holding more or less than expected.
- **Nearby-aware requests**: Holding `Ctrl` allows the chain planner to use cached nearby containers along with your inventory.
- **Partial fallback**: If the full requested amount cannot be crafted but at least one final output can, the mod can offer to craft the possible amount instead.
- **Conservative execution**: Intermediate results go into your inventory, and each step still uses the normal autocraft placement and validation path.
- **Tidy hotbar**: Leftover intermediates such as planks and sticks go to your main inventory, so the final item lands in your first free hotbar slot.
- **Across variants**: With **Output Variant Switching** on, a chain offer reports what every family variant can reach together (pale oak stairs requested with five kinds of logs on hand), and after the first variant's chain finishes the next variant chains without asking again.

---

## Bulk Crafting

- **Enable bulk mode**: Hold `Alt` and click the crafting result arrow. Bulk mode is shown with an orange outline around the arrow. It remains on even after alt is released until a craft has been completed or aborted, or toggled back off. 
- **What bulk changes**: Bulk uncaps the normal queue limit, making it practical to craft large amounts of stackable and nonstackable items such as dispensers or cake if you wish. 
- **Bulk max craft**: `Ctrl + Shift + click` while bulk is enabled repeatedly performs nearby max crafts until resources run out or the session is aborted.
- **Dynamic staging and ejection**: Bulk craft brings in as many items as possible at a time and can eject outputs when needed to keep large sessions moving.
- **Output variant switching**: With the **Output Variant Switching** setting on, a bulk craft, a plain auto craft, or a retrieval can move on to another variant in the same family once the current one runs out. Ask for 32 stairs with planks for 20 oak and 12 birch and you get both. Off keeps one variant per request.
- **Safe shutdown**: Bulk sessions shut off automatically when finished, when manually aborted with `ESC`, or when the game window loses focus.

---

## Bulk Chain Crafting

Bulk chain crafting combines bulk mode with chain crafting: request hundreds of an item while only base materials sit in nearby chests, and the mod repeatedly chain crafts batches until the requested amount is reached or materials run out.

- **How to start**: With bulk mode on, request an amount (`Ctrl + Alt + scroll`) or use `Ctrl + Shift + click` for as much as materials allow. If materials fall short, the confirmation shows the achievable amount up front (requesting 200 dispensers with materials for 173 offers 173).
- **Inventory-sized batches**: Each batch is planned fresh from your current inventory and nearby containers, and sized so staged materials, intermediates, and outputs actually fit. Unstackable intermediates like bows shrink the batch instead of clogging your inventory.
- **Leftovers are used, not wasted**: Byproducts and surplus intermediates from one batch (extra slabs, planks, sticks) count as available materials when the next batch is planned.
- **Cleanup**: Leftover pulled materials are returned to their source chests when the session finishes. If your inventory becomes too full to continue, the session stops with a clear message instead of throwing items.
- **Safe shutdown**: Same rules as bulk: `Esc`, closing the screen, or losing window focus aborts the session cleanly.
- **Settings**: Toggle the feature with **Bulk Chain Crafting** in the mod settings. The chain crafting mode (ask first / always) applies to the first confirmation.

---

## Retrieving Existing Items

If copies of what you are about to craft already sit in a nearby chest (the green dot), a `Ctrl` recipe click can pull them out instead of crafting duplicates. Plain clicks are never affected.

- **Existing Output Handling** (mod settings) decides what happens:
  - **Retrieve, Then Ask** (default): pull up to the requested amount, then ask whether to craft the rest. Requesting 32 stairs with 25 in a chest pulls the 25 and offers to craft the remaining 7.
  - **Retrieve, Then Craft**: same, but the rest is crafted without asking.
  - **Retrieve Only**: pull up to the requested amount and stop.
  - **Craft Only**: never look in chests for the output.
- **Whole crafts only**: Only whole crafts' worth of an item is retrieved before crafting the rest. Queuing one craft of diamonds (a diamond block makes 9) with 3 diamonds nearby crafts the 9 and leaves the 3 in the chest.
- **Never more than asked**: The pull never exceeds what you requested, and never throws items on the ground. `Ctrl + Shift + click` pulls everything nearby before the max craft.

---

## Retrieval Mode

Retrieval Mode is a state exclusive to nearby crafting that lets you pull existing items directly from nearby chests instead of crafting them.

- **Enable retrieval mode**: Hold `Ctrl` and click the result slot, or double-tap `Ctrl` (ensure neither autocraft nor bulk craft are toggled on).
- **What retrieval changes**: Indicated by an 'X' in the result slot, this mode tells the recipe book to retrieve items from nearby containers into your inventory instead of crafting them. Manual crafting remains possible while this mode is active.
- **Any modifier retrieves**: Since the mode only ever pulls from nearby containers, `Alt` works the same as `Ctrl` while it is on.
- **Uncapped**: `Shift + click` retrieves everything nearby, and `Ctrl + scroll` counts as high as bulk crafting does. When your inventory fills and **Eject New Items When Inventory Full** is on, the rest is thrown on the ground straight from the chest; with it off the session stops at the full inventory.
- **Expanded visibility**: Shows icons for all items you have seen at least once in-game, including those with no crafting recipes.
- **Availability indicators**: A green dot on an item icon denotes that at least 1 of that item is currently available in nearby containers to be retrieved.

---

## Scroll To Pull

Scroll to pull is a specialized fast-output workflow that pairs well with the `Spacebar` x16 multiplier.

- `Shift + scroll down` while hovering the result slot pulls the crafted result into your inventory, or ejects it if inventory space is unavailable.
- `Shift + scroll down` while hovering an inventory slot tries to pull the crafted result directly into that slot.
- `Shift + scroll up` appends the crafted result to your cursor stack.

---

## Core Mechanics

### Intelligent Resource Management

- **Variant resolution**: The mod can resolve revolving recipe variants based on your request, the selected variant, your settings, and the resources currently available.
- **Smart chest interaction**: Nearby pulls favor near-empty slots to help keep storage clean and consolidated.
- **Item return and inventory restore**: The mod remembers your inventory and chest layout and tries to return items to their original places after crafting.
- **High-performance caching**: Nearby container contents are cached to speed up repeated crafts and power the recipe indicators.
- **Remembered containers**: With **Remember Containers** (on by default), the cache is saved for each world, so your chests stay known after you walk away, leave or relaunch instead of being opened again. Changes are saved the moment they are seen. Single-player worlds keep it in an `effortless-crafting` folder inside the save, and deleting it is always safe.

### Nearby Crafting

- **Nearby container usage**: `Ctrl` lets recipe requests use reachable storage around you in addition to your inventory.
- **Furnaces**: Furnaces, blast furnaces and smokers can be pulled from too. **Furnace Slots** decides which slots: **Output Only** (default) takes finished items and never disturbs a running smelt, and **Output and Input** and **All Slots** are also available. Items are never returned into a furnace.
- **Exact variant requests**: Right click on revolving recipes to open vanilla's variant picker when you want one exact recipe instead of family-based resolution.
- **Large nearby requests**: Nearby requests can combine with autocraft and bulk for large multi-stage crafting sessions.

### Server Rate Limits

Many multiplayer servers (Paper and similar) limit recipe placement packets and silently drop the excess, which can make big crafts crawl. The mod paces its placements against a self-tuning budget: it starts conservatively, learns each server's real limit, and remembers it per server, so big crafts stay fast without tripping the limiter.

- **No setup required**: it adapts automatically.
- **Settings > Advanced > Server Rate Limits** exposes the starting and maximum rates and an on/off toggle if you want to pin a known limit.
- **Playing an older version through a proxy?** On servers joined through ViaVersion/ViaBackwards, **Minecraft 1.21.4 and older** clients can misread crafted items because the proxy rewrites item data. Use a same-version server for reliable bulk and chain crafting on those versions. **1.21.5 and newer** are unaffected.

---

## Quality Of Life Tweaks

### Smart Item Transfer

- **Hotbar left-to-right**: Automatically placed crafted items fill the hotbar from left to right instead of vanilla's usual right-to-left behavior.
- **Offhand stacking**: Crafted items can be stacked into the offhand slot and work for both 2x2 and 3x3 crafting grids.

### Recipe Book And UI Enhancements

- **Type to search**: Typing can jump directly into the recipe book search bar while ignoring keys that should not become text input.
- **Search history**: Use the up and down arrows to cycle through previous recipe book searches.
- **Smart recipe sorting**: The recipe book can prioritize recent crafts, directly craftable recipes, chain-craftable recipes, and nearby-craftable recipes while keeping vanilla sorting available in settings.
- **3x3 auto-focus**: The search bar is automatically focused when opening a crafting table.
- **Yield and queue indicators**: The UI can show output totals and queued counts directly on recipes.
- **Chat feedback**: Missing ingredient reports and bulk craft summaries are surfaced in chat.
- **Recipe filter toggle**: `Spacebar` still supports the craftable/uncraftable recipe filter toggle when not being used for request scaling.
- **Recipe book navigation**: Scroll or use arrow keys to easily page through the recipe book.
- **Revolving craft menus & tooltips**: Expanding recipes with multiple variants now clearly displays accurate tooltips for each variant.

### Extra Controls

- **Quick Craft hotkey**: `B` by default opens a nearby 3x3 crafting table when possible, otherwise falls back to the 2x2 inventory grid and focuses search.
- **In-game help**: `/effortlesscrafting help` lists short pages for every feature (`/effortlesscrafting help bulk`), `/effortlesscrafting tips` collects the easy-to-miss shortcuts, and `/effortlesscrafting settings` opens the settings screen without Mod Menu or a keybind.

---

## Customization And Filtering

### High Customizability

Effortless Crafting is designed to fit different playstyles. Most behavior can be adjusted in the mod settings with Mod Menu and Cloth Config, including autocraft handling, variant behavior, queue behavior, and whether the mod is enabled at all.

### Container Filtering System

- **Global container blacklist**: Prevent entire container types such as hoppers or ender chests from being used unless explicitly allowed with the in-world whitelist.
- **In-world blacklist / whitelist**: With in-world filtering enabled, hold `Ctrl` inside a container UI to inspect and change that container's blacklist, neutral, or whitelist state via a clickable dot near the top of the container's UI.
- **Visual wireframes**: Optional wireframes show filtered container states in the world.

---

## Credits And License

This project is licensed under **LGPL-3.0-or-later**.

It draws inspiration and certain implementation patterns from:

- [stack-to-nearby-chests](https://github.com/xiaocihua/stack-to-nearby-chests) by xiaocihua (LGPL-3.0)

## Questions
If you have questions feel free to message me at https://www.reddit.com/user/ekra8154/ or through issues on the Github repo
