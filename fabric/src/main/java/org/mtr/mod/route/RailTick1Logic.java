package org.mtr.mod.route;

import org.mtr.core.data.Client;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;

/**
 * Behaviour-preserving pieces of the Rail tick1 replacement kept outside the
 * mixin class so the optimized path has a tracked, directly testable source.
 */
public final class RailTick1Logic {

	private RailTick1Logic() {
	}

	public static boolean needsUpdate(
			Long2LongAVLTreeMap preBlockedVehicleIds,
			Long2LongAVLTreeMap preBlockedVehicleIdsOld,
			Long2LongAVLTreeMap currentlyBlockedVehicleIds,
			Long2LongAVLTreeMap currentlyBlockedVehicleIdsOld
	) {
		return !org.mtr.core.tool.Utilities.sameItems(preBlockedVehicleIds.keySet(), preBlockedVehicleIdsOld.keySet())
				|| !org.mtr.core.tool.Utilities.sameItems(currentlyBlockedVehicleIds.keySet(), currentlyBlockedVehicleIdsOld.keySet());
	}

	public static void updateClients(
			Rail rail,
			Simulator simulator,
			boolean needsUpdate
	) {
		if (simulator.clients.isEmpty()) return;
		for (final Client client : simulator.clients) {
			final Position position = client.getPosition();
			if (rail.closeTo(position, client.getUpdateRadius())) {
				client.update(rail, needsUpdate);
			}
		}
	}

	/**
	 * Returns the old map after clearing it. The caller assigns the returned
	 * reference to the current field and moves the previous current reference
	 * into the old field. This is the same reference rotation as Rail.tick1,
	 * without copying the AVL tree.
	 */
	public static Long2LongAVLTreeMap demoteAndClear(Long2LongAVLTreeMap current, Long2LongAVLTreeMap old) {
		old.clear();
		return old;
	}
}
