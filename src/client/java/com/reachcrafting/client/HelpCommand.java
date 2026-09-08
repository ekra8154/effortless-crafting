package com.reachcrafting.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.reachcrafting.client.mixin.ChatComponentAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/**
 * {@code /effortlesscrafting} - in-game help, tips, and a settings shortcut.
 *
 * <p>Help renders as a PAGE, not a log append: the exact components of the
 * last page are remembered and deleted from chat history before the next page
 * prints, so clicking through the index navigates instead of stacking pages.
 * Only lines printed through {@link #line} are ever touched; ordinary chat
 * stays where it is. Same scheme as Tupenter's help pager, so the two mods
 * read alike: aqua title, gray lead-in then white body, dark gray footnotes,
 * white/gray clickable rows, a dark gray back link.
 */
public final class HelpCommand {
	private static final String ROOT = "effortlesscrafting";
	private static final String CMD = "/" + ROOT;

	/** Canonical topic names, in index order; these are what tab completion offers. */
	private static final List<String> TOPICS = List.of(
		"general", "queuing", "nearby", "autocrafting", "chain", "bulk", "bulkchain", "retrieval", "retrievethencraft"
	);
	/** Spellings a player might reasonably type, folded to the canonical name. */
	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("queue", "queuing"), Map.entry("queueing", "queuing"), Map.entry("shift", "queuing"),
		Map.entry("request", "queuing"), Map.entry("requests", "queuing"), Map.entry("normal", "queuing"),
		Map.entry("nearbycrafting", "nearby"), Map.entry("containers", "nearby"), Map.entry("chests", "nearby"),
		Map.entry("ctrl", "nearby"),
		Map.entry("autocraft", "autocrafting"), Map.entry("auto", "autocrafting"), Map.entry("alt", "autocrafting"),
		Map.entry("chaincrafting", "chain"), Map.entry("chains", "chain"),
		Map.entry("bulkcrafting", "bulk"),
		Map.entry("bulkchaincrafting", "bulkchain"), Map.entry("bulkchains", "bulkchain"),
		Map.entry("retrievalmode", "retrieval"), Map.entry("retrieve", "retrieval"), Map.entry("retrieving", "retrieval"),
		Map.entry("retrievethenask", "retrievethencraft"), Map.entry("existingoutput", "retrievethencraft"),
		Map.entry("existingoutputhandling", "retrievethencraft"), Map.entry("retrieval-then-craft", "retrievethencraft"),
		Map.entry("outputvariantswitching", "autocrafting"), Map.entry("variants", "queuing"),
		Map.entry("basics", "general"), Map.entry("start", "general"), Map.entry("quickstart", "general"),
		Map.entry("modifiers", "general"), Map.entry("overview", "general"), Map.entry("controls", "general"),
		Map.entry("tips", "tips"), Map.entry("index", "index"), Map.entry("topics", "index")
	);

	private HelpCommand() {
	}

	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(literal(ROOT)
				.executes(context -> runIndex())
				.then(literal("help")
					.executes(context -> runIndex())
					.then(argument("topic", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(suggestions(), builder))
						.executes(context -> runTopic(StringArgumentType.getString(context, "topic")))))
				.then(literal("tips").executes(context -> runTips()))
				.then(literal("settings").executes(HelpCommand::runSettings))
			)
		);
	}

	private static List<String> suggestions() {
		List<String> names = new ArrayList<>(TOPICS);
		names.add("tips");
		return names;
	}

	/** Dev harness: run the same handlers a typed command would, by their argument words. */
	static void harnessRun(String args) {
		String[] parts = args.trim().split("\\s+");
		if (parts.length == 0 || parts[0].isEmpty() || parts[0].equals("help") && parts.length == 1) {
			runIndex();
		} else if (parts[0].equals("help")) {
			runTopic(parts[1]);
		} else if (parts[0].equals("tips")) {
			runTips();
		} else if (parts[0].equals("settings")) {
			openSettings();
		} else {
			runTopic(parts[0]);
		}
	}

	// ---- pages ---------------------------------------------------------------

	static int runIndex() {
		return runIndex(null);
	}

	/** The index, optionally headed by a notice (an unknown topic) that must live on the same page to survive. */
	private static int runIndex(String notice) {
		begin();
		if (notice != null) {
			line(notice);
		}
		line("§bEffortless Crafting help — pick a topic:");
		line(row("general", "the basics: the three modifiers, click vs scroll, stacking them", "general"));
		line(row("tips", "the easy-to-miss shortcuts", "tips"));
		line(row("queuing", "Shift requests: hover, scroll an amount, release", "queuing"));
		line(row("nearby", "Ctrl: craft from chests and barrels around you", "nearby"));
		line(row("autocrafting", "Alt: craft the request and pocket the result", "autocrafting"));
		line(row("chain", "missing intermediates get crafted first", "chain"));
		line(row("bulk", "uncapped amounts and repeated max crafts", "bulk"));
		line(row("bulkchain", "hundreds of something from base materials", "bulkchain"));
		line(row("retrieval", "Retrieval Mode: clicks pull items out of chests", "retrieval"));
		line(row("retrievethencraft", "a Ctrl click grabs copies you already own first", "retrievethencraft"));
		line(row(CMD + " settings", "open the settings screen from chat", "settings"));
		line("§8Also: " + CMD + " help <topic>");
		end();
		return 1;
	}

	static int runTopic(String rawTopic) {
		String topic = rawTopic.toLowerCase(Locale.ROOT).replace("_", "");
		topic = ALIASES.getOrDefault(topic, topic);
		return switch (topic) {
			case "index" -> runIndex();
			case "tips" -> runTips();
			case "general" -> page(new Object[]{
				"§bEffortless Crafting — the basics:",
				"§7A request:§r hover a recipe, hold a modifier, scroll to pick the amount, release to send it. Or just click with the modifier held.",
				"§7Shift§r - inventory only. Click: vanilla's max craft, as much as fits in the grid. Scroll: queue an amount.",
				"§7Ctrl§r - nearby chests allowed. Click: adds one, drawing on nearby storage. Scroll: queue an amount, sent when Ctrl is released.",
				"§7Alt§r - autocraft it for you. Click: crafts one instantly. Scroll: queue an amount, crafted when Alt is released.",
				"§7Stack them:§r Ctrl + Alt + scroll is a nearby autocraft. Ctrl + Shift + click is a nearby max craft. Ctrl + Alt + Shift + click is a nearby max craft, autocrafted.",
				"§7While scrolling:§r hold Space to count by 16; right click or Esc cancels. §7Esc§r aborts any session the mod is running.",
				"§7Bigger:§r Alt + click the result arrow latches bulk (no cap, repeated max crafts). Double-tap Ctrl for Retrieval Mode (clicks pull items out of chests).",
				"§7The dots§r on a recipe: a filled dot crafts from your inventory, a plus shape means nearby chests are needed. Yellow is a direct craft, orange a chain craft, and green underneath means copies already sit in a chest.",
				"§7Vanilla stays:§r plain clicks, Shift + click, Space to re-place the last recipe, and right click for the variant menu all work as before.",
				link(CMD + " settings", ChatFormatting.WHITE, CMD + " settings")
					.append(Component.literal(" opens the settings; the other topics go deeper.").withStyle(ChatFormatting.GRAY)),
			});
			case "queuing" -> page(new String[]{
				"§bQueuing a request (Shift):",
				"§7Hover a recipe, hold Shift, scroll§r to set how many, then release Shift to send it. The counter on the recipe shows the queued amount.",
				"§7Space§r while scrolling counts by 16. §7Right click or Esc§r before releasing cancels the queue.",
				"§7Shift + click§r is still vanilla's max craft: as much as fits in the grid at once.",
				"§7Inventory only.§r A Shift request uses what you carry. Hold §fCtrl§r instead (or as well) to let it pull from nearby chests, or §fAlt§r to have the result crafted for you.",
				"§7Revolving recipes§r (oak fence / spruce fence on one button) craft the variant shown, under §fRevolving Craft Handling§r. Right click the recipe for vanilla's variant menu when you want one exact variant.",
				"§8Settings: Input Counter Visibility, Revolving Craft Handling, Count Preference.",
			});
			case "nearby" -> page(new String[]{
				"§bNearby crafting (Ctrl):",
				"§7Ctrl§r lets a request use the chests, barrels and shulker boxes within reach as well as your inventory. §7Ctrl + click§r adds one; §7Ctrl + scroll§r queues an amount and sends it when Ctrl is released; §7Ctrl + Shift + click§r is a max craft from nearby storage.",
				"§7What happens:§r materials are pulled from chests into the grid, the craft is staged, and anything left over goes back where it came from when you close the grid.",
				"§7The dots§r on a recipe say what a Ctrl click would do: a filled dot crafts from your inventory alone, a plus shape means nearby chests are needed. Yellow is a direct craft, orange a chain craft.",
				"§7First look is slow, later ones fast:§r container contents are remembered, so the first request walks each chest once and later requests know where things are.",
				"§7Keep a chest out of it:§r set §fIn-World Black/Whitelist§r to Blacklist (off by default), then hold Ctrl inside any container and click the dot near the top to blacklist (or whitelist) it. Quicker: with the filter outlines showing (the Toggle Filtered Container Outlines key), sneak + right-click a container with an empty hand to cycle it. Whole container types can be excluded with §fBlacklisted Container Types§r.",
				"§8Settings: Nearby Container Usage (While Ctrl Held / Always On), Nearby Container Caching, Container Drain Order, In-World Black/Whitelist, Blacklisted Container Types.",
			});
			case "autocrafting" -> page(new String[]{
				"§bAutocrafting (Alt):",
				"§7Alt§r marks a request as an autocraft: the recipe is staged, crafted, and the result moved into your inventory. The arrow in the result slot shows when autocraft is active.",
				"§7Alt + click§r crafts one right away. §7Alt + scroll§r queues an amount and crafts it when Alt is released. §7Alt + Shift + click§r is a max craft, crafted. Add §fCtrl§r to any of them for nearby chests.",
				"§7Already staged?§r Tap Alt with a recipe sitting in the grid and it crafts that, no need to rebuild the request. Recipes you placed by hand are never crafted otherwise.",
				"§7Hold or Toggle:§r by default Alt counts while held. With §fAuto Craft Handling§r set to Toggle, a tap switches autocraft on and off instead.",
				"§7Full inventory:§r results are dropped on the ground when nothing fits, if §fEject New Items When Inventory Full§r is on (it is by default).",
				"§7Ran out of one variant?§r §fOutput Variant Switching§r lets an autocraft or a retrieval carry on with another variant of the same family, following Revolving Craft Handling. Off by default.",
				"§8Settings: Auto Crafting, Auto Craft Handling, Alt Click Instant Craft, Alt as Request Key, Offhand Stacking.",
			});
			case "chain" -> page(new String[]{
				"§bChain crafting:",
				"§7When an autocraft is missing something craftable,§r chain crafting makes that first. Ask for a comparator with logs, redstone, stone and quartz: planks, sticks and torches get crafted on the way.",
				"§7Ask First§r (default) shows a prompt with what it will make; §7Always§r just does it; §7Disabled§r turns it off. Set with §fChain Crafting§r.",
				"§7Not enough for all of it?§r The prompt offers the amount that is reachable instead.",
				"§7With Ctrl§r the plan may draw on nearby chests too. An orange dot on a recipe means it is chain craftable from what is around.",
				"§7Intermediates§r land in your inventory step by step. §7Esc§r aborts a running chain.",
				"§7Big requests:§r a bulk request that needs intermediates becomes a bulk chain session, see " + CMD + " help bulkchain.",
				"§8Settings: Chain Crafting, Chain Craft Messages, Output Variant Switching (the prompt then counts every variant).",
			});
			case "bulk" -> page(new String[]{
				"§bBulk crafting:",
				"§7Turn it on:§r hold Alt and click the arrow in the result slot. An orange outline around the arrow means bulk is latched. It stays on until a session finishes or is aborted, or you click it off.",
				"§7What changes:§r the request cap is gone, so §7Ctrl + Alt + scroll§r can ask for hundreds, and §7Ctrl + Shift + click§r repeats nearby max crafts until materials run out.",
				"§7Non-stackables too:§r dispensers, cakes, bows - the session stages what fits, ejects results when the inventory is full, and keeps going.",
				"§7Stopping:§r §fEsc§r, closing the screen, or the game losing focus ends the session cleanly. A chat summary reports what was made.",
				"§7Needs§r §fAuto Crafting§r set to \"Auto Crafting and Bulk Crafting\" (the default).",
				"§8Settings: Toggle Auto Craft Off After Bulk, Bulk Craft Summary, Bulk Despawn Warning, Eject New Items When Inventory Full.",
			});
			case "bulkchain" -> page(new String[]{
				"§bBulk chain crafting:",
				"§7Bulk and chain together:§r ask for 200 dispensers with only cobblestone, redstone, bows and string in chests, and the mod chain crafts batch after batch until it gets there or runs out.",
				"§7Start it§r with bulk latched (Alt + click the arrow), then §7Ctrl + Alt + scroll§r an amount or §7Ctrl + Shift + click§r for as many as materials allow. The prompt shows the reachable amount up front.",
				"§7Batches§r are sized to your inventory: staged materials, intermediates and outputs all have to fit. Leftovers from one batch (spare planks, sticks) feed the next.",
				"§7Cleanup:§r pulled materials go back to their chests when the session ends. If the inventory gets too full to continue, it stops with a message rather than throwing things.",
				"§7Stopping§r works like bulk: Esc, closing the screen, or losing focus.",
				"§8Settings: Bulk Chain Crafting (on by default); Chain Crafting decides whether it asks first.",
			});
			case "retrieval" -> page(new String[]{
				"§bRetrieval Mode:",
				"§7Turn it on:§r double-tap Ctrl in a crafting screen, or Ctrl + click the result slot (with autocraft and bulk off). An X in the result slot means it is active; the same gesture turns it off.",
				"§7What changes:§r recipe clicks pull the item out of nearby chests instead of crafting it. §7Click§r takes one, §7Shift + click§r takes everything nearby, §7Ctrl + scroll§r queues an amount.",
				"§7No recipe needed:§r items with no recipe (eggs, ender pearls) show up in the book too, from everything you have seen at least once.",
				"§7Green dot§r on an entry means some are in a chest within reach. Right click a revolving entry for its variant menu, one colour per entry.",
				"§7Full inventory:§r with §fEject New Items When Inventory Full§r on, the rest is thrown on the ground straight from the chest; off, the pull stops when you are full.",
				"§8Settings: Existing Output Retrieval (turns the mode off entirely), Retrievable Indicator, Output Variant Switching.",
			});
			case "retrievethencraft" -> page(new String[]{
				"§bRetrieve, then craft:",
				"§7Copies already in a chest?§r Outside Retrieval Mode, a Ctrl-assisted click on a recipe whose output sits in nearby storage (the green dot under the craftable dot) pulls those first instead of crafting duplicates. Plain clicks are untouched.",
				"§7Existing Output Handling§r decides what happens next:",
				" §fRetrieve, Then Ask§r (default): pull up to the requested amount, then ask whether to craft the rest.",
				" §fRetrieve, Then Craft§r: same, but the rest is crafted without asking.",
				" §fRetrieve Only§r: pull up to the amount and stop; with none nearby the click is an ordinary craft.",
				" §fCraft Only§r: never look in chests for the output.",
				"§7Count-bound:§r it never pulls more than you asked for. §7Ctrl + Shift + click§r pulls everything nearby before the max craft. A pull from a craft click never drops items; it fills the inventory and stops.",
				"§8Settings: Existing Output Handling, Retrievable Indicator.",
			});
			default -> runIndex("§cNo help topic called \"" + rawTopic + "\". Pick one:");
		};
	}

	static int runTips() {
		return page(new String[]{
			"§bTips - the easy-to-miss shortcuts:",
			"§7Double-tap Ctrl§r in a crafting screen toggles Retrieval Mode. Ctrl + click the result slot does the same; Alt + click it latches bulk.",
			"§7Space§r while scrolling a request counts by 16. §7Double-tap Space§r (the craftable filter key) toggles the craftable-only filter.",
			"§7Scroll over the recipe book§r or press §7Left / Right§r to flip pages.",
			"§7Just start typing§r with the book open and the search bar takes it. §7Up / Down§r walk your search history.",
			"§7B§r opens the nearest crafting table, or your inventory grid with the search focused when none is in reach (Quick Craft key).",
			"§7Shift + scroll down§r over the result slot pulls the result into your inventory, or into the inventory slot you are hovering. §7Shift + scroll up§r stacks it onto your cursor.",
			"§7Tap Alt§r with a recipe already in the grid to craft it - no need to rebuild the request.",
			"§7Right click or Esc§r cancels a queue you are still scrolling; §7Esc§r aborts any running session.",
			"§7Hover a variant§r in the expanded recipe menu for its tooltip; the menu closes itself after a request.",
			"§7Hold Ctrl inside a chest§r to see and set its blacklist / whitelist dot (needs In-World Black/Whitelist on); with the filter outlines showing, sneak + right-click a container with an empty hand to cycle it.",
			"§7Green under yellow:§r the green dot below the craftable dot means copies of the output are already in a nearby chest.",
		});
	}

	// ---- settings -------------------------------------------------------------

	private static boolean settingsPending;

	private static int runSettings(CommandContext<FabricClientCommandSource> context) {
		if (!openSettings()) {
			context.getSource().sendError(Component.literal(CLOTH_MISSING));
			return 0;
		}
		return 1;
	}

	private static final String CLOTH_MISSING =
		"The settings screen needs Cloth Config, which isn't installed. Everything else still works.";

	/**
	 * Ask for the settings screen; false if Cloth Config is missing.
	 *
	 * <p>The open has to happen on a LATER tick: a client command runs inside
	 * the chat screen's Enter handling, which closes the chat screen right
	 * afterwards, so a screen opened during the command is closed at once.
	 * Minecraft.execute does not defer on the render thread either, so a flag
	 * picked up from the client tick is used instead.
	 */
	static boolean openSettings() {
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return false;
		}
		settingsPending = true;
		return true;
	}

	/** Called once per client tick from the mod's tick handler. */
	static void tickPendingSettings(Minecraft client) {
		if (!settingsPending) {
			return;
		}
		settingsPending = false;
		if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
			client.setScreen(com.reachcrafting.client.compat.ReachCraftingConfigScreen.create(null));
		}
	}

	// ---- the pager -----------------------------------------------------------

	private static final List<Component> PAGE_LINES = new ArrayList<>();

	private static void begin() {
		Minecraft client = Minecraft.getInstance();
		if (client.gui == null || PAGE_LINES.isEmpty()) {
			PAGE_LINES.clear();
			return;
		}
		ChatComponent chat = client.gui.getChat();
		// identity, not equals: delete the exact component instances added
		Set<Component> previous = Collections.newSetFromMap(new IdentityHashMap<>());
		previous.addAll(PAGE_LINES);
		ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
		if (accessor.reachcrafting$allMessages().removeIf(message -> previous.contains(message.content()))) {
			accessor.reachcrafting$refreshTrimmedMessages();
		}
		PAGE_LINES.clear();
	}

	private static void line(Component component) {
		PAGE_LINES.add(component);
		Minecraft.getInstance().gui.getChat().addClientSystemMessage(component);
	}

	private static void line(String text) {
		line(Component.literal(text));
	}

	/** Close the page: say so if anything is clickable, then snap chat to the bottom so the page is looked at. */
	private static void end() {
		boolean clickable = false;
		for (Component component : PAGE_LINES) {
			if (hasClick(component)) {
				clickable = true;
				break;
			}
		}
		if (clickable) {
			line("§8§oHover a line to see what clicking it does.");
		}
		Minecraft.getInstance().gui.getChat().resetChatScroll();
	}

	private static boolean hasClick(Component component) {
		if (component.getStyle().getClickEvent() != null) {
			return true;
		}
		for (Component sibling : component.getSiblings()) {
			if (hasClick(sibling)) {
				return true;
			}
		}
		return false;
	}

	/** A whole page of section-styled strings (or styled components), replacing the previous page, with a back link to the index. */
	private static int page(Object[] lines) {
		begin();
		for (Object entry : lines) {
			if (entry instanceof Component component) {
				line(component);
			} else {
				line((String) entry);
			}
		}
		line(link("« help topics", ChatFormatting.DARK_GRAY, CMD + " help"));
		end();
		return 1;
	}

	/** Clickable line that runs a navigation-only command; hover shows the command. */
	private static net.minecraft.network.chat.MutableComponent link(String label, ChatFormatting color, String command) {
		return Component.literal(label).withStyle(style -> style
			.withColor(color)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	/**
	 * An index row: white head, gray description, the WHOLE line clickable.
	 * Built from styled components, not section codes, which reset the style
	 * mid-line and punch holes in the click region.
	 */
	private static Component row(String head, String desc, String sub) {
		String command = sub.equals("tips") || sub.equals("settings") ? CMD + " " + sub : CMD + " help " + sub;
		return Component.literal(" " + head).withStyle(ChatFormatting.WHITE)
			.append(Component.literal(" — " + desc).withStyle(ChatFormatting.GRAY))
			.withStyle(style -> style
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}
}
