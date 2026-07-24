package org.mtr.mod.block;

import org.mtr.mapping.holder.CompoundTag;

import javax.annotation.Nullable;
import java.util.Arrays;

public final class RailwaySignTextData {

	public static final String FREE_TEXT_SIGN_ID = "free_text";
	public static final String FREE_TEXT_FLIPPED_SIGN_ID = "free_text_flipped";
	public static final int MAX_TEXT_LENGTH = 1024;
	private static final String KEY_CUSTOM_TEXT = "custom_text_";

	private final String[] texts;

	public RailwaySignTextData(int length) {
		texts = new String[length];
		Arrays.fill(texts, "");
	}

	public void read(CompoundTag compoundTag, String[] signIds) {
		if (signIds.length != texts.length) {
			return;
		}
		final String[] newTexts = new String[texts.length];
		for (int i = 0; i < newTexts.length; i++) {
			newTexts[i] = isFreeText(signIds[i]) ? sanitize(compoundTag.getString(KEY_CUSTOM_TEXT + i)) : "";
		}
		System.arraycopy(newTexts, 0, texts, 0, texts.length);
	}

	public void write(CompoundTag compoundTag) {
		for (int i = 0; i < texts.length; i++) {
			compoundTag.putString(KEY_CUSTOM_TEXT + i, texts[i]);
		}
	}

	public boolean replace(String[] signIds, String[] newTexts) {
		if (signIds.length != texts.length || newTexts.length != texts.length) {
			return false;
		}
		final String[] sanitizedTexts = new String[texts.length];
		for (int i = 0; i < sanitizedTexts.length; i++) {
			sanitizedTexts[i] = isFreeText(signIds[i]) ? sanitize(newTexts[i]) : "";
		}
		System.arraycopy(sanitizedTexts, 0, texts, 0, texts.length);
		return true;
	}

	public void setText(int index, @Nullable String text) {
		if (index >= 0 && index < texts.length) {
			texts[index] = sanitize(text);
		}
	}

	public void onSignChanged(int index, @Nullable String signId) {
		if (index >= 0 && index < texts.length && !isFreeText(signId)) {
			texts[index] = "";
		}
	}

	public String getText(int index) {
		return index >= 0 && index < texts.length ? texts[index] : "";
	}

	public String[] getTexts() {
		return texts;
	}

	public static boolean isFreeText(@Nullable String signId) {
		return FREE_TEXT_SIGN_ID.equals(signId) || FREE_TEXT_FLIPPED_SIGN_ID.equals(signId);
	}

	public static String sanitize(@Nullable String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
	}
}
