package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.render.MipmapCompute.Step;
import java.nio.ByteBuffer;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/**
 * Holds the dispatches that fill a chain by compute to the levels the image has: every level past
 * the base written once, a tile only over levels that halve exactly, and no step past the bindings
 * the shader declares.
 */
class MipmapComputeTest {

	private static final int TILE = MipmapCompute.TILE;
	private static final int SINGLE = MipmapCompute.SINGLE;
	private static final int TAIL = MipmapCompute.TAIL;

	@Test
	void fullHd() {
		// 960x540, 480x270, 240x135 halve; 120x67 and 60x33 do not; 30x16 is the first under the
		// tail size.
		assertEquals(List.of(new Step(TILE, 1, 3), new Step(SINGLE, 4, 1), new Step(SINGLE, 5, 1),
				new Step(TAIL, 6, 5)), MipmapCompute.plan(1920, 1080, 11));
		assertArrayEquals(new int[] {30, 17}, MipmapCompute.groups(new Step(TILE, 1, 3), 1920, 1080));
		assertArrayEquals(new int[] {8, 5}, MipmapCompute.groups(new Step(SINGLE, 4, 1), 1920, 1080));
		assertArrayEquals(new int[] {4, 3}, MipmapCompute.groups(new Step(SINGLE, 5, 1), 1920, 1080));
		assertArrayEquals(new int[] {1, 1}, MipmapCompute.groups(new Step(TAIL, 6, 5), 1920, 1080));
	}

	@Test
	void otherScreens() {
		// 2560x1440 halves down to 80x45, then 40x22 is tail.
		assertEquals(List.of(new Step(TILE, 1, 5), new Step(TAIL, 6, 6)),
				MipmapCompute.plan(2560, 1440, 12));
		assertEquals(List.of(new Step(TILE, 1, 4), new Step(SINGLE, 5, 1), new Step(SINGLE, 6, 1),
				new Step(TAIL, 7, 5)), MipmapCompute.plan(3840, 2160, 12));
		// An odd base: 500 is not half of 1001, 250 and 125 halve, 62 does not.
		assertEquals(List.of(new Step(SINGLE, 1, 1), new Step(TILE, 2, 2), new Step(SINGLE, 4, 1),
				new Step(TAIL, 5, 5)), MipmapCompute.plan(1001, 1001, 10));
		// A strip whose tail outruns one dispatch.
		assertEquals(List.of(new Step(TILE, 1, 3), new Step(TAIL, 4, 7), new Step(TAIL, 11, 4)),
				MipmapCompute.plan(16384, 8, 15));
		assertEquals(List.of(), MipmapCompute.plan(1, 1, 1));
	}

	@Test
	void everyLevelOnceWithinTheBindings() {
		for (int width = 1; width <= 4100; width += 37) {
			for (int height = 1; height <= 2200; height += 53) {
				int levels = TargetSurface.levelsFor(true, width, height);
				int next = 1;
				for (Step step : MipmapCompute.plan(width, height, levels)) {
					assertEquals(next, step.first(), width + "x" + height);
					assertTrue(step.count() >= 1);
					switch (step.mode()) {
						case TILE -> {
							assertTrue(step.count() >= 2 && step.count() <= MipmapCompute.TILE_LEVELS);
							for (int level = step.first(); level < step.first() + step.count(); level++) {
								assertEquals(MipmapCompute.extent(width, level - 1),
										2 * MipmapCompute.extent(width, level), width + "x" + height);
								assertEquals(MipmapCompute.extent(height, level - 1),
										2 * MipmapCompute.extent(height, level), width + "x" + height);
							}
						}
						case SINGLE -> assertEquals(1, step.count());
						default -> {
							assertTrue(step.count() <= MipmapCompute.TAIL_LEVELS);
							assertTrue((long) MipmapCompute.extent(width, step.first())
									* MipmapCompute.extent(height, step.first()) <= MipmapCompute.TAIL_TEXELS);
							// The shader fills every level after the first two texels per invocation.
							assertTrue((long) MipmapCompute.extent(width, step.first() + 1)
									* MipmapCompute.extent(height, step.first() + 1) <= 2 * MipmapCompute.GROUP,
									width + "x" + height);
						}
					}
					next += step.count();
				}
				assertEquals(levels, next, width + "x" + height);
			}
		}
	}

	@Test
	void shaderCompilesForEveryFormatItServes() {
		List<String> qualifiers = MipmapCompute.qualifiers();
		assertTrue(qualifiers.contains("rgba8") && qualifiers.contains("r11f_g11f_b10f"));
		for (String qualifier : qualifiers) {
			ByteBuffer spirv = MipmapCompute.compile(MipmapCompute.source(qualifier));
			assertNotNull(spirv, qualifier);
			MemoryUtil.memFree(spirv);
		}
	}
}
