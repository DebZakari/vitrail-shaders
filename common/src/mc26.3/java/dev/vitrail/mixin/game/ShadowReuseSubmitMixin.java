package dev.vitrail.mixin.game;

import dev.vitrail.render.ShadowReuse;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Brackets each entity's submission, so that {@link ShadowReuse} can mark what a caster submits.
 * <p>
 * Around the dispatcher's {@code submit} and not the renderer's: it is the one call that submits the
 * body, the flames and the ground oval of an entity together, whichever renderer the entity has, and
 * the outermost of nested calls decides for everything inside it. The end is in a {@code finally},
 * so a submission that throws and is caught further up leaves nothing marked that should not be.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadowReuseSubmitMixin {

	@WrapMethod(method = "submit", require = 1)
	private void vitrail$mark(EntityRenderState state, CameraRenderState camera, double x, double y,
			double z, PoseStack poseStack, SubmitNodeCollector collector, Operation<Void> original) {
		boolean was = ShadowReuse.enter(state);
		try {
			original.call(state, camera, x, y, z, poseStack, collector);
		} finally {
			ShadowReuse.leave(was);
		}
	}
}
