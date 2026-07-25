package org.mtr.mod.packet;

import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;

public final class RouteAssetPacketCodec {

	private RouteAssetPacketCodec() {
	}

	public static String readBoundedString(PacketBufferReceiver receiver, int maximumCharacters, int maximumUtf8Bytes) {
		final int length = receiver.readInt();
		return readBoundedString(length, ignored -> receiver.readChar(), maximumCharacters, maximumUtf8Bytes);
	}

	public static String readBoundedString(int length, IntFunction<Character> characterReader, int maximumCharacters, int maximumUtf8Bytes) {
		if (length < 0 || length > maximumCharacters) {
			throw new IllegalArgumentException("Route asset packet string length is out of bounds");
		}
		final StringBuilder builder = new StringBuilder(length);
		for (int index = 0; index < length; index++) {
			builder.append(characterReader.apply(index).charValue());
		}
		return requireBounded(builder.toString(), maximumCharacters, maximumUtf8Bytes);
	}

	public static void writeBoundedString(PacketBufferSender sender, String value, int maximumCharacters, int maximumUtf8Bytes) {
		final String bounded = requireBounded(value, maximumCharacters, maximumUtf8Bytes);
		sender.writeInt(bounded.length());
		for (int index = 0; index < bounded.length(); index++) sender.writeChar(bounded.charAt(index));
	}

	public static String requireBounded(String value, int maximumCharacters, int maximumUtf8Bytes) {
		final String text = Objects.requireNonNull(value, "value");
		if (text.length() > maximumCharacters || text.getBytes(StandardCharsets.UTF_8).length > maximumUtf8Bytes) {
			throw new IllegalArgumentException("Route asset packet string is out of bounds");
		}
		return text;
	}

	public static int readBoundedCount(PacketBufferReceiver receiver, int maximum) {
		return requireBoundedCount(receiver.readInt(), maximum);
	}

	public static int requireBoundedCount(int count, int maximum) {
		if (count < 0 || count > maximum) throw new IllegalArgumentException("Route asset packet count is out of bounds");
		return count;
	}

	public static byte[] readBoundedBytes(PacketBufferReceiver receiver, int maximumBytes) {
		final int length = receiver.readInt();
		return readBoundedBytes(length, ignored -> receiver.readChar(), maximumBytes);
	}

	public static byte[] readBoundedBytes(int length, IntFunction<Character> byteReader, int maximumBytes) {
		if (length < 0 || length > maximumBytes) throw new IllegalArgumentException("Route asset packet byte length is out of bounds");
		final byte[] bytes = new byte[length];
		for (int index = 0; index < length; index++) {
			final int value = byteReader.apply(index);
			if (value < 0 || value > 255) throw new IllegalArgumentException("Route asset packet byte value is out of bounds");
			bytes[index] = (byte) value;
		}
		return bytes;
	}

	public static void writeBoundedBytes(PacketBufferSender sender, byte[] bytes, int maximumBytes) {
		final byte[] bounded = requireBoundedBytes(bytes, maximumBytes);
		sender.writeInt(bounded.length);
		for (final byte value : bounded) sender.writeChar((char) (value & 0xFF));
	}

	public static byte[] requireBoundedBytes(byte[] bytes, int maximumBytes) {
		final byte[] value = Objects.requireNonNull(bytes, "bytes");
		if (maximumBytes < 0 || value.length > maximumBytes) throw new IllegalArgumentException("Route asset packet bytes are out of bounds");
		return Arrays.copyOf(value, value.length);
	}

	public static <T extends Enum<T>> T decodeEnum(Class<T> type, int ordinal) {
		final T[] values = type.getEnumConstants();
		if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("Invalid route asset packet enum");
		return values[ordinal];
	}
}
