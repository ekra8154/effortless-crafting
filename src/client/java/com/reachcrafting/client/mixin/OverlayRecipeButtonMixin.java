package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeButtonNearbyIndicator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent$OverlayRecipeButton")
public abstract class OverlayRecipeButtonMixin {
	@Shadow
	@Final
	private RecipeDisplayId recipe;

	@Shadow
	@Final
	OverlayRecipeComponent this$0;

	@Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
	private void reachcrafting$renderQueuedCount(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		RecipeCollection collection = this$0.getRecipeCollection();
		if (collection == null) {
			return;
		}

		AbstractWidget widget = (AbstractWidget) (Object) this;
		RecipeButtonNearbyIndicator.renderOverlayButton(
			guiGraphics,
			widget.getX(),
			widget.getY(),
			widget.getWidth(),
			recipe,
			collection
		);

		if (ReachCraftingConfig.get().expandedVariantMenuTooltips()) {
			widget.setTooltip(null);
			if (widget.isHovered()) {
				net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
				net.minecraft.util.context.ContextMap context = net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(minecraft.level);
				for (net.minecraft.world.item.crafting.display.RecipeDisplayEntry entry : collection.getRecipes()) {
					if (entry.id().equals(recipe)) {
						net.minecraft.world.item.ItemStack stack = com.reachcrafting.client.RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
						if (!stack.isEmpty()) {
							guiGraphics.setTooltipForNextFrame(
								minecraft.font,
								java.util.List.of(stack.getHoverName().getVisualOrderText()),
								net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE,
								mouseX,
								mouseY,
								true
							);
						}
						break;
					}
				}
			}
		} else {
			widget.setTooltip(null);
		}
	}
}
