package org.mtr.mod.route;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import org.mtr.mod.data.JavaCompat;
import java.nio.file.Paths;

/**
 * Small, dependency-free CPU topology helper used only for choosing a safe default
 * for off-thread route-asset generation.
 */
final class CpuTopology {

	private final int logicalProcessors;
	private final int physicalCores;

	private CpuTopology(int logicalProcessors, int physicalCores) {
		this.logicalProcessors = Math.max(1, logicalProcessors);
		this.physicalCores = Math.max(1, Math.min(this.logicalProcessors, physicalCores));
	}

	static CpuTopology detect() {
		final int logical = Math.max(1, Runtime.getRuntime().availableProcessors());
		if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
			try {
				final OptionalInt parsedPhysical = parsePhysicalCores(JavaCompat.readString(Paths.get("/proc/cpuinfo")));
				if (parsedPhysical.isPresent()) return new CpuTopology(logical, parsedPhysical.getAsInt());
			} catch (IOException | RuntimeException ignored) {
				// The logical/quota-aware fallback below is deliberately conservative.
			}
		}
		return new CpuTopology(logical, Math.max(1, logical / 2));
	}

	static OptionalInt parsePhysicalCores(String procCpuinfo) {
		final Set<String> physicalCoreIds = new HashSet<>();
		Integer physicalId = null;
		Integer coreId = null;
		for (final String rawLine : procCpuinfo == null ? new String[0] : procCpuinfo.split("\\R")) {
			final String line = rawLine.trim();
			if (line.isEmpty()) {
				addPhysicalCore(physicalCoreIds, physicalId, coreId);
				physicalId = null;
				coreId = null;
				continue;
			}
			final int separator = line.indexOf(':');
			if (separator < 0) continue;
			final String key = line.substring(0, separator).trim();
			final String value = line.substring(separator + 1).trim();
			try {
				if ("physical id".equals(key)) {
					physicalId = Integer.parseInt(value);
				} else if ("core id".equals(key)) {
					coreId = Integer.parseInt(value);
				}
			} catch (NumberFormatException ignored) {
				// A malformed record is ignored; another complete record may still be usable.
			}
		}
		addPhysicalCore(physicalCoreIds, physicalId, coreId);
		return physicalCoreIds.isEmpty() ? OptionalInt.empty() : OptionalInt.of(physicalCoreIds.size());
	}

	static int recommendedGenerationThreads(int logicalProcessors, int physicalCores) {
		final int logical = Math.max(1, logicalProcessors);
		final int physical = Math.max(1, Math.min(logical, physicalCores > 0 ? physicalCores : logical / 2));
		return Math.max(1, Math.min(4, physical / 2 - 1));
	}

	int getLogicalProcessors() {
		return logicalProcessors;
	}

	int getPhysicalCores() {
		return physicalCores;
	}

	private static void addPhysicalCore(Set<String> physicalCoreIds, Integer physicalId, Integer coreId) {
		if (physicalId != null && coreId != null) {
			physicalCoreIds.add(physicalId + ":" + coreId);
		}
	}
}
