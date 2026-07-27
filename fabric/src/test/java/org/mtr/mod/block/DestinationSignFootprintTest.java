package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.Direction;

import java.nio.file.Files;
import java.nio.file.Path;
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
					if (width == 1 && height == 1) continue;
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
		Assertions.assertEquals(508, combinations);
	}

	@Test
	public void acceptsTwoCellPortraitAndLandscapeButRejectsOneCell() {
		Assertions.assertEquals(2, DestinationSignFootprint.cells(new BlockPos(0, 0, 0), Direction.NORTH, 1, 2).size());
		Assertions.assertEquals(2, DestinationSignFootprint.cells(new BlockPos(0, 0, 0), Direction.NORTH, 2, 1).size());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> DestinationSignFootprint.cells(new BlockPos(0, 0, 0), Direction.NORTH, 1, 1));
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

	@Test
	public void resizeUsesMappedReplaceabilityInsteadOfLoaderSpecificRawStateMethods() throws Exception {
		Path source = Path.of("src", "main", "java", "org", "mtr", "mod", "block", "BlockDestinationSign.java");
		if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
		final String block = Files.readString(source);
		Assertions.assertFalse(block.contains("current.data.isReplaceable()"));
		Assertions.assertTrue(block.contains("state.canReplace(context)"));
	}

	@Test
	public void interactionDistanceUsesTheNearestOwnedFootprintCell() throws Exception {
		Path source = Path.of("src", "main", "java", "org", "mtr", "mod", "block", "BlockDestinationSign.java");
		if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
		final String block = Files.readString(source);
		Assertions.assertTrue(block.contains("if (isOwnedBy(world, cell.getPosition(), anchor)) ownedCells.add(cell.getPosition())"));
		Assertions.assertTrue(block.contains("return withinInteractionDistance(ownedCells, playerX, playerY, playerZ)"));
		Assertions.assertTrue(block.contains("if (x * x + y * y + z * z <= 64) return true"));
	}

	@Test
	public void applyConfigReturnsTheBoundedResultType() throws Exception {
		Assertions.assertEquals(DestinationSignConfigResult.class, BlockDestinationSign.class.getDeclaredMethod(
				"applyConfig", org.mtr.mapping.holder.World.class, BlockPos.class,
				org.mtr.mapping.holder.PlayerEntity.class, DestinationSignConfig.class).getReturnType());
	}

	@Test
	public void applyConfigGuardsEveryRollbackSurfaceAndIndexesDensity() throws Exception {
		Path source = Path.of("src", "main", "java", "org", "mtr", "mod", "block", "BlockDestinationSign.java");
		if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
		final String block = Files.readString(source);
		Assertions.assertTrue(block.contains("restoreWorldStateQuietly(world, originalStates)"));
		Assertions.assertTrue(block.contains("restoreEntityConfigQuietly(entity, previous)"));
		Assertions.assertTrue(block.contains("restoreConfiguredIndexQuietly(persistentState, anchor, previous)"));
		Assertions.assertTrue(block.contains("config.isShowEta(), config.getRoutesPerBlockHeight())"));
		Assertions.assertTrue(block.contains("if (indexChanged) notifyConfiguredAssetsQuietly(world, \"destination-sign-config\")"));
		Assertions.assertTrue(block.contains("Unable to notify Destination Sign neighbors after a committed save"));
	}
}
