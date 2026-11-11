package dev.vitrail.render;

import net.minecraft.world.entity.Entity;

/**
 * The 26.2 half, which lends nothing: the light's walk extracts every caster again, as it always has.
 * <p>
 * The lending rests on 26.3's feature pipeline, where a draw is built per group of submissions and
 * the grouping can be steered; 26.2 builds and draws its features otherwise.
 */
public final class ShadowReuse {

	private ShadowReuse() {
	}

	static void gathered(boolean entities) {
	}

	static boolean skip(Entity entity) {
		return false;
	}

	static boolean replaying() {
		return false;
	}

	static int lent() {
		return 0;
	}

	static void replay() {
	}

	static void walked() {
	}
}
