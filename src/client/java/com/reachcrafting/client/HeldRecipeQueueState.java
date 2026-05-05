package com.reachcrafting.client;

final class HeldRecipeQueueState {
	private RecipeBookClickCapture.PendingHeldRecipe pendingHeldRecipe;
	private RecipeBookClickCapture.ReplayBatch replayBatch;
	private boolean wasControlDown;
	private boolean wasShiftDown;
	private boolean wasSearchBoxFocusedByMod;
	private boolean wasModifierReleasedWhileSpaceHeld;
	private int replayDelayTicks;

	RecipeBookClickCapture.PendingHeldRecipe pendingHeldRecipe() {
		return pendingHeldRecipe;
	}

	void setPendingHeldRecipe(RecipeBookClickCapture.PendingHeldRecipe pendingHeldRecipe) {
		this.pendingHeldRecipe = pendingHeldRecipe;
	}

	RecipeBookClickCapture.ReplayBatch replayBatch() {
		return replayBatch;
	}

	void setReplayBatch(RecipeBookClickCapture.ReplayBatch replayBatch) {
		this.replayBatch = replayBatch;
	}

	boolean wasControlDown() {
		return wasControlDown;
	}

	void setWasControlDown(boolean wasControlDown) {
		this.wasControlDown = wasControlDown;
	}

	boolean wasShiftDown() {
		return wasShiftDown;
	}

	void setWasShiftDown(boolean wasShiftDown) {
		this.wasShiftDown = wasShiftDown;
	}

	boolean wasSearchBoxFocusedByMod() {
		return wasSearchBoxFocusedByMod;
	}

	void setWasSearchBoxFocusedByMod(boolean wasSearchBoxFocusedByMod) {
		this.wasSearchBoxFocusedByMod = wasSearchBoxFocusedByMod;
	}

	boolean wasModifierReleasedWhileSpaceHeld() {
		return wasModifierReleasedWhileSpaceHeld;
	}

	void setWasModifierReleasedWhileSpaceHeld(boolean wasModifierReleasedWhileSpaceHeld) {
		this.wasModifierReleasedWhileSpaceHeld = wasModifierReleasedWhileSpaceHeld;
	}

	int replayDelayTicks() {
		return replayDelayTicks;
	}

	void setReplayDelayTicks(int replayDelayTicks) {
		this.replayDelayTicks = replayDelayTicks;
	}

	void decrementReplayDelayTicks() {
		replayDelayTicks--;
	}

	void clear() {
		pendingHeldRecipe = null;
		replayBatch = null;
		wasSearchBoxFocusedByMod = false;
		wasModifierReleasedWhileSpaceHeld = false;
		replayDelayTicks = 0;
	}
}
