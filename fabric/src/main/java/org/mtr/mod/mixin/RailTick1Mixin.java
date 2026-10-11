package org.mtr.mixin;

import org.mtr.core.data.Rail;
import org.mtr.core.data.Position;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.mod.route.RailTick1Logic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes two per-rail costs that the stock Transport-Simulation-Core pays on every simulation tick.
 * <p>
 * The core ticks each dimension at a fixed 10 ms period, so {@code Rail.tick1} runs 100 times per second for
 * every rail in the world. The original body builds both blocked-vehicle maps by copying an AVL tree into
 * another AVL tree, and computes a client update flag that is only ever read by the client callbacks. On a
 * CPU with weak single core performance that dominates the dedicated server's idle cost.
 * <p>
 * Both replacements are behaviour preserving:
 * <ul>
 *     <li>The four maps are private, final, and never aliased outside {@code Rail}, so demoting them by
 *     reference leaves the contents of all four fields identical to the original clear/copy/clear sequence.
 *     Because it is a sorted map, iteration order is unchanged.</li>
 *     <li>{@code needsUpdate} is a pure comparison whose only consumer is the client loop, so evaluating it
 *     when no client is present cannot change any observable state.</li>
 * </ul>
 */
@Mixin(value = Rail.class, remap = false)
public abstract class RailTick1Mixin {

	@Shadow
	@Final
	@Mutable
	private Long2LongAVLTreeMap preBlockedVehicleIds;

	@Shadow
	@Final
	@Mutable
	private Long2LongAVLTreeMap currentlyBlockedVehicleIds;

	@Shadow
	@Final
	@Mutable
	private Long2LongAVLTreeMap preBlockedVehicleIdsOld;

	@Shadow
	@Final
	@Mutable
	private Long2LongAVLTreeMap currentlyBlockedVehicleIdsOld;

	@Shadow
	public abstract boolean closeTo(Position position, double radius);

	@Inject(method = "tick1(Lorg/mtr/core/simulation/Simulator;)V", at = @At("HEAD"), cancellable = true)
	private void mtr$tick1(Simulator simulator, CallbackInfo callbackInfo) {
		if (!simulator.clients.isEmpty()) {
			final Rail rail = (Rail) (Object) this;
			final boolean needsUpdate = RailTick1Logic.needsUpdate(preBlockedVehicleIds, preBlockedVehicleIdsOld, currentlyBlockedVehicleIds, currentlyBlockedVehicleIdsOld);
			RailTick1Logic.updateClients(rail, simulator, needsUpdate);
		}

		final Long2LongAVLTreeMap demotedPre = RailTick1Logic.demoteAndClear(preBlockedVehicleIds, preBlockedVehicleIdsOld);
		preBlockedVehicleIdsOld = preBlockedVehicleIds;
		preBlockedVehicleIds = demotedPre;

		final Long2LongAVLTreeMap demotedCurrently = RailTick1Logic.demoteAndClear(currentlyBlockedVehicleIds, currentlyBlockedVehicleIdsOld);
		currentlyBlockedVehicleIdsOld = currentlyBlockedVehicleIds;
		currentlyBlockedVehicleIds = demotedCurrently;
		callbackInfo.cancel();
	}

}
