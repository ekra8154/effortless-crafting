package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeBookClickCapture;
import com.reachcrafting.client.RecipeVariantResolver;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookPage.class)
public abstract class RecipeBookPageMixin {
	@Shadow
	@Final
	private List<RecipeButton> buttons;

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$interceptRecipeButtonClick(
		double mouseX,
		double mouseY,
		int button,
		int left,
		int top,
		int width,
		int height,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
			if (overlay != null && overlay.isVisible()) {
				return;
			}

			for (RecipeButton recipeButton : this.buttons) {
				if (!recipeButton.isMouseOver(mouseX, mouseY)) {
					continue;
				}

				ItemStack displayStack = RecipeVariantResolver.resolveDisplayStack(recipeButton.getRecipe(), Minecraft.getInstance());
				if (recipeButton.getCollection() != null && recipeButton.getCollection().getRecipes().size() > 1) {
					if (RecipeBookClickCapture.onRecipeButtonRightClicked(
						recipeButton.getRecipe(),
						recipeButton.getCollection(),
						displayStack,
						false
					)) {
						cir.setReturnValue(true);
					}
					return;
				}

				if (RecipeBookClickCapture.onRecipeButtonRightClicked(
					recipeButton.getRecipe(),
					recipeButton.getCollection(),
					displayStack,
					false
				)) {
					cir.setReturnValue(true);
				}
				return;
			}
		}

		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		boolean ctrlDown = Screen.hasControlDown();
		boolean shiftDown = Screen.hasShiftDown();
		boolean altDown = Screen.hasAltDown();
		if (shiftDown) {
			RecipeBookClickCapture.defocusRecipeBookSearch(Minecraft.getInstance());
		}

		OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
		if (overlay != null && overlay.isVisible()) {
			return;
		}

		boolean interceptWithMod = ctrlDown || (altDown && !shiftDown && ReachCraftingConfig.get().altAsRequestKey());
		if (!interceptWithMod) {
			return;
		}

		for (RecipeButton recipeButton : this.buttons) {
			if (!recipeButton.isMouseOver(mouseX, mouseY)) {
				continue;
			}

			RecipeBookClickCapture.onRecipeButtonClicked(
				recipeButton.getRecipe(),
				recipeButton.getCollection(),
				RecipeVariantResolver.resolveDisplayStack(recipeButton.getRecipe(), Minecraft.getInstance()),
				button,
				shiftDown,
				ctrlDown,
				altDown,
				false
			);
			cir.setReturnValue(true);
			return;
		}
	}

	@Inject(method = "mouseClicked", at = @At("RETURN"))
	private void reachcrafting$afterVanillaRecipeButtonClick(
		double mouseX,
		double mouseY,
		int button,
		int left,
		int top,
		int width,
		int height,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!ReachCraftingConfig.get().enabled() || !cir.getReturnValueZ() || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		if (Screen.hasControlDown()) {
			return;
		}
		boolean altDown = Screen.hasAltDown();

		for (RecipeButton recipeButton : this.buttons) {
			if (!recipeButton.isMouseOver(mouseX, mouseY)) {
				continue;
			}

			RecipeBookClickCapture.onVanillaRecipeButtonClicked(
				recipeButton.getRecipe(),
				recipeButton.getCollection(),
				RecipeVariantResolver.resolveDisplayStack(recipeButton.getRecipe(), Minecraft.getInstance()),
				false,
				altDown
			);
			return;
		}
	}
}
