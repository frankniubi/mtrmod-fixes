package org.mtr.mod.route;

public enum DestinationSignStyle {
	ARRIVAL_ORDER(1, 42),
	PLATFORM_GROUPS(1, 48),
	DESTINATION_FLAG(1, 56);

	private final int minimumWidthBlocks;
	private final int rowHeight;

	DestinationSignStyle(int minimumWidthBlocks, int rowHeight) {
		this.minimumWidthBlocks = minimumWidthBlocks;
		this.rowHeight = rowHeight;
	}

	public int getMinimumWidthBlocks() { return minimumWidthBlocks; }
	public int getRowHeight() { return rowHeight; }
}
