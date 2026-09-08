package com.reachcrafting.client.mixin;

import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Chat internals for the /effortlesscrafting help pager: the previous page's
 * messages are deleted from chat history so pages REPLACE each other instead
 * of piling up.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

	@Accessor("allMessages")
	List<GuiMessage> reachcrafting$allMessages();

	@Invoker("refreshTrimmedMessages")
	void reachcrafting$refreshTrimmedMessages();
}
