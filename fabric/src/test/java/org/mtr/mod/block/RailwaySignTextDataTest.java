package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.CompoundTag;

public final class RailwaySignTextDataTest {

	@Test
	public void ownsIndependentPerSlotTextAndClearsNonFreeSigns() {
		final RailwaySignTextData first = new RailwaySignTextData(3);
		final RailwaySignTextData second = new RailwaySignTextData(3);
		first.setText(0, "車站資訊|Station Information");
		first.setText(1, "Second");

		Assertions.assertEquals("車站資訊|Station Information", first.getText(0));
		Assertions.assertEquals("Second", first.getText(1));
		Assertions.assertEquals("", second.getText(0), "text ownership must remain on one Railway Sign block entity");

		first.onSignChanged(0, RailwaySignTextData.FREE_TEXT_FLIPPED_SIGN_ID);
		Assertions.assertEquals("車站資訊|Station Information", first.getText(0), "alignment changes preserve text");
		first.onSignChanged(0, "exit_letter");
		Assertions.assertEquals("", first.getText(0), "non-free signs cannot retain hidden free text");
	}

	@Test
	public void persistsUnicodeAndLoadsOldNbtAsEmpty() {
		final String[] signIds = {RailwaySignTextData.FREE_TEXT_SIGN_ID, RailwaySignTextData.FREE_TEXT_FLIPPED_SIGN_ID};
		final RailwaySignTextData oldData = new RailwaySignTextData(2);
		oldData.read(new CompoundTag(), signIds);
		Assertions.assertArrayEquals(new String[]{"", ""}, oldData.getTexts());

		oldData.setText(0, "出口方向|Exit Direction");
		oldData.setText(1, "機場 Airport");
		final CompoundTag compoundTag = new CompoundTag();
		oldData.write(compoundTag);

		final RailwaySignTextData loaded = new RailwaySignTextData(2);
		loaded.read(compoundTag, signIds);
		Assertions.assertArrayEquals(new String[]{"出口方向|Exit Direction", "機場 Airport"}, loaded.getTexts());
	}

	@Test
	public void validatesReplacementAtomicallyAndCapsTextLength() {
		final RailwaySignTextData data = new RailwaySignTextData(2);
		Assertions.assertTrue(data.replace(
				new String[]{RailwaySignTextData.FREE_TEXT_SIGN_ID, RailwaySignTextData.FREE_TEXT_FLIPPED_SIGN_ID},
				new String[]{"Before", "Other"}
		));
		Assertions.assertFalse(data.replace(new String[]{RailwaySignTextData.FREE_TEXT_SIGN_ID}, new String[]{"Partial"}));
		Assertions.assertArrayEquals(new String[]{"Before", "Other"}, data.getTexts(), "invalid arrays must not partially mutate state");

		final String tooLong = "x".repeat(RailwaySignTextData.MAX_TEXT_LENGTH + 50);
		data.setText(0, tooLong);
		Assertions.assertEquals(RailwaySignTextData.MAX_TEXT_LENGTH, data.getText(0).length());

		Assertions.assertTrue(data.replace(
				new String[]{"exit_letter", RailwaySignTextData.FREE_TEXT_FLIPPED_SIGN_ID},
				new String[]{"Must clear", "Kept"}
		));
		Assertions.assertArrayEquals(new String[]{"", "Kept"}, data.getTexts());
	}
}
