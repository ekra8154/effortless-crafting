package com.reachcrafting.client;

final class HeldRecipeQueueState {
	private RecipeBookClickCapture.PendingHeldRecipe pendingHeldRecipe;
	private RecipeBookClickCapture.ReplayBatch replayBatch;
	private boolean wasControlDown;
	private boolean wasShiftDown;
	private boolean wasAltDown;
	private boolean wasSearchBoxFocusedByMod;
	private boolean wasModifierReleasedWhileSpaceHeld;
	private int controlReleaseWindowTicks;
	private int shiftReleaseWindowTicks;
	private int altReleaseWindowTicks;
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

	boolean wasAltDown() {
		return wasAltDown;
	}

	void setWasAltDown(boolean wasAltDown) {
		this.wasAltDown = wasAltDown;
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

	int controlReleaseWindowTicks() {
		return controlReleaseWindowTicks;
	}

	void setControlReleaseWindowTicks(int controlReleaseWindowTicks) {
		this.controlReleaseWindowTicks = Math.max(controlReleaseWindowTicks, 0);
	}

	int shiftReleaseWindowTicks() {
		return shiftReleaseWindowTicks;
	}

	void setShiftReleaseWindowTicks(int shiftReleaseWindowTicks) {
		this.shiftReleaseWindowTicks = Math.max(shiftReleaseWindowTicks, 0);
	}

	int altReleaseWindowTicks() {
		return altReleaseWindowTicks;
	}

	void setAltReleaseWindowTicks(int altReleaseWindowTicks) {
		this.altReleaseWindowTicks = Math.max(altReleaseWindowTicks, 0);
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
		wasControlDown = false;
		wasShiftDown = false;
		wasAltDown = false;
		wasSearchBoxFocusedByMod = false;
		wasModifierReleasedWhileSpaceHeld = false;
		controlReleaseWindowTicks = 0;
		shiftReleaseWindowTicks = 0;
		altReleaseWindowTicks = 0;
		replayDelayTicks = 0;
	}
}
