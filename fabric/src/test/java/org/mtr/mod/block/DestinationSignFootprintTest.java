package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.Direction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DestinationSignFootprintTest {

	@Test
	public void everyLegalCellResolvesTheSameReadableFaceAnchor() {
		final BlockPos anchor = new BlockPos(37, -63, -91);
		int combinations = 0;
		for (final Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
			for (int width = DestinationSignFootprint.MIN_WIDTH; width <= DestinationSignFootprint.MAX_WIDTH; width++) {
				for (int height = DestinationSignFootprint.MIN_HEIGHT; height <= DestinationSignFootprint.MAX_HEIGHT; height++) {
					combinations++;
					final List<DestinationSignFootprint.Cell> cells = DestinationSignFootprint.cells(anchor, facing, width, height);
					Assertions.assertEquals(width * height, cells.size());
					for (final DestinationSignFootprint.Cell cell : cells) {
						Assertions.assertEquals(anchor, DestinationSignFootprint.anchor(cell.getPosition(), facing, cell.getHorizontalOffset(), cell.getVerticalOffset()));
						Assertions.assertEquals(cell.getPosition(), DestinationSignFootprint.cell(anchor, facing, cell.getHorizontalOffset(), cell.getVerticalOffset()));
					}
				}
			}
		}
		Assertions.assertEquals(420, combinations);
	}

	@Test
	public void resizeAndRemovalPlansOnlyTouchCellsOwnedByTheAnchor() {
		final BlockPos anchor = new BlockPos(-4, 12, 8);
		final DestinationSignFootprint.Mutation resize = DestinationSignFootprint.resize(anchor, Direction.WEST, 4, 3, 2, 2);
		Assertions.assertEquals(4, resize.getRetained().size());
		Assertions.assertEquals(8, resize.getRemoved().size());
		Assertions.assertTrue(resize.getAdded().isEmpty());
		Assertions.assertTrue(resize.getRemoved().stream().allMatch(cell -> cell.getHorizontalOffset() >= 2 || cell.getVerticalOffset() >= 2));
		Assertions.assertEquals(12, DestinationSignFootprint.removal(anchor, Direction.WEST, 4, 3).getRemoved().size());
	}

	@Test
	public void failedTransactionRestoresEveryCapturedValue() {
		final Map<BlockPos, String> values = new HashMap<>();
		final BlockPos first = new BlockPos(0, 0, 0);
		final BlockPos second = new BlockPos(1, 0, 0);
		final BlockPos third = new BlockPos(2, 0, 0);
		values.put(first, "first-old");
		values.put(second, "second-old");
		values.put(third, "third-old");

		final boolean committed = DestinationSignFootprint.transact(List.of(first, second, third), new DestinationSignFootprint.TransactionAccess<String>() {
			@Override
			public String capture(BlockPos position) {
				return values.get(position);
			}

			@Override
			public boolean write(BlockPos position) {
				values.put(position, position.equals(first) ? "first-new" : "partial-new");
				return !position.equals(second);
			}

			@Override
			public void restore(BlockPos position, String value) {
				values.put(position, value);
			}
		});

		Assertions.assertFalse(committed);
		Assertions.assertEquals("first-old", values.get(first));
		Assertions.assertEquals("second-old", values.get(second));
		Assertions.assertEquals("third-old", values.get(third));
	}
}
