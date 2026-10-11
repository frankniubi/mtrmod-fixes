package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

public final class CpuTopologyTest {

	@Test
	public void parsesSingleAndDualSocketPhysicalCorePairs() {
		final String singleSocket = """
				processor : 0
				physical id : 0
				core id : 0

				processor : 1
				physical id : 0
				core id : 1
				""";
		final String dualSocket = """
				processor : 0
				physical id : 0
				core id : 0

				processor : 1
				physical id : 1
				core id : 0

				processor : 2
				physical id : 1
				core id : 1
				""";

		Assertions.assertEquals(OptionalInt.of(2), CpuTopology.parsePhysicalCores(singleSocket));
		Assertions.assertEquals(OptionalInt.of(3), CpuTopology.parsePhysicalCores(dualSocket));
	}

	@Test
	public void missingOrMalformedPhysicalFieldsFallBackToEmpty() {
		Assertions.assertEquals(OptionalInt.empty(), CpuTopology.parsePhysicalCores(""));
		Assertions.assertEquals(OptionalInt.empty(), CpuTopology.parsePhysicalCores("processor : 0\ncore id : not-a-number"));
		Assertions.assertEquals(OptionalInt.empty(), CpuTopology.parsePhysicalCores("processor : 0\nphysical id : 0"));
	}

	@Test
	public void recommendedThreadsUsePhysicalCoresAndCapAutomaticMode() {
		Assertions.assertEquals(1, CpuTopology.recommendedGenerationThreads(4, 2));
		Assertions.assertEquals(1, CpuTopology.recommendedGenerationThreads(8, 4));
		Assertions.assertEquals(4, CpuTopology.recommendedGenerationThreads(20, 10));
		Assertions.assertEquals(4, CpuTopology.recommendedGenerationThreads(40, 20));
		Assertions.assertEquals(1, CpuTopology.recommendedGenerationThreads(1, 1));
		Assertions.assertEquals(1, CpuTopology.recommendedGenerationThreads(4, 0));
	}
}
