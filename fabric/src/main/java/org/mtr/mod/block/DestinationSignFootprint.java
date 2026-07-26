package org.mtr.mod.block;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical geometry for a sign whose anchor is the readable face's lower-left cell. */
public final class DestinationSignFootprint {

	public static final int MIN_WIDTH = 2;
	public static final int MAX_WIDTH = 16;
	public static final int MIN_HEIGHT = 2;
	public static final int MAX_HEIGHT = 8;

	private DestinationSignFootprint() { }

	public static BlockPos cell(BlockPos anchor, Direction facing, int horizontalOffset, int verticalOffset) {
		validateFacing(facing);
		return anchor.offset(facing.rotateYCounterclockwise(), horizontalOffset).up(verticalOffset);
	}

	public static BlockPos anchor(BlockPos cell, Direction facing, int horizontalOffset, int verticalOffset) {
		validateFacing(facing);
		return cell.offset(facing.rotateYClockwise(), horizontalOffset).down(verticalOffset);
	}

	public static List<Cell> cells(BlockPos anchor, Direction facing, int width, int height) {
		validateDimensions(width, height);
		final List<Cell> cells = new ArrayList<>(width * height);
		for (int verticalOffset = 0; verticalOffset < height; verticalOffset++) {
			for (int horizontalOffset = 0; horizontalOffset < width; horizontalOffset++) {
				cells.add(new Cell(cell(anchor, facing, horizontalOffset, verticalOffset), horizontalOffset, verticalOffset));
			}
		}
		return Collections.unmodifiableList(cells);
	}

	public static Mutation resize(BlockPos anchor, Direction facing, int oldWidth, int oldHeight, int newWidth, int newHeight) {
		final Map<BlockPos, Cell> oldCells = index(cells(anchor, facing, oldWidth, oldHeight));
		final Map<BlockPos, Cell> newCells = index(cells(anchor, facing, newWidth, newHeight));
		final List<Cell> retained = new ArrayList<>();
		final List<Cell> removed = new ArrayList<>();
		final List<Cell> added = new ArrayList<>();
		oldCells.forEach((position, oldCell) -> {
			if (newCells.containsKey(position)) retained.add(newCells.get(position));
			else removed.add(oldCell);
		});
		newCells.forEach((position, newCell) -> {
			if (!oldCells.containsKey(position)) added.add(newCell);
		});
		return new Mutation(retained, removed, added);
	}

	public static Mutation removal(BlockPos anchor, Direction facing, int width, int height) {
		return new Mutation(List.of(), cells(anchor, facing, width, height), List.of());
	}

	public static <T> boolean transact(List<BlockPos> positions, TransactionAccess<T> access) {
		final Map<BlockPos, T> captured = new LinkedHashMap<>();
		try {
			for (final BlockPos position : positions) captured.put(position, access.capture(position));
			for (final BlockPos position : positions) if (!access.write(position)) throw new TransactionRejectedException();
			return true;
		} catch (RuntimeException exception) {
			final List<Map.Entry<BlockPos, T>> entries = new ArrayList<>(captured.entrySet());
			Collections.reverse(entries);
			for (final Map.Entry<BlockPos, T> entry : entries) access.restore(entry.getKey(), entry.getValue());
			return false;
		}
	}

	public static void validateDimensions(int width, int height) {
		if (width < MIN_WIDTH || width > MAX_WIDTH || height < MIN_HEIGHT || height > MAX_HEIGHT) {
			throw new IllegalArgumentException("Invalid destination sign footprint");
		}
	}

	private static Map<BlockPos, Cell> index(List<Cell> cells) {
		final Map<BlockPos, Cell> indexed = new LinkedHashMap<>();
		cells.forEach(cell -> indexed.put(cell.position, cell));
		return indexed;
	}

	private static void validateFacing(Direction facing) {
		if (facing != Direction.NORTH && facing != Direction.EAST && facing != Direction.SOUTH && facing != Direction.WEST) {
			throw new IllegalArgumentException("Destination sign facing must be horizontal");
		}
	}

	public interface TransactionAccess<T> {
		T capture(BlockPos position);
		boolean write(BlockPos position);
		void restore(BlockPos position, T value);
	}

	public static final class Cell {
		private final BlockPos position;
		private final int horizontalOffset;
		private final int verticalOffset;

		private Cell(BlockPos position, int horizontalOffset, int verticalOffset) {
			this.position = Objects.requireNonNull(position, "position");
			this.horizontalOffset = horizontalOffset;
			this.verticalOffset = verticalOffset;
		}

		public BlockPos getPosition() { return position; }
		public int getHorizontalOffset() { return horizontalOffset; }
		public int getVerticalOffset() { return verticalOffset; }
	}

	public static final class Mutation {
		private final List<Cell> retained;
		private final List<Cell> removed;
		private final List<Cell> added;

		private Mutation(List<Cell> retained, List<Cell> removed, List<Cell> added) {
			this.retained = List.copyOf(retained);
			this.removed = List.copyOf(removed);
			this.added = List.copyOf(added);
		}

		public List<Cell> getRetained() { return retained; }
		public List<Cell> getRemoved() { return removed; }
		public List<Cell> getAdded() { return added; }
	}

	private static final class TransactionRejectedException extends RuntimeException { }
}
