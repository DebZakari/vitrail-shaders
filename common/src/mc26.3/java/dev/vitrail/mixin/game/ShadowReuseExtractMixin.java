package dev.vitrail.mixin.game;

import dev.vitrail.render.ShadowReuse;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link ShadowReuse} which entities the picture extracts, each with the state it extracted.
 * <p>
 * At the call of {@code extractEntity} inside the picture's own loop and not inside
 * {@code extractEntity} itself: the light's walk extracts through the dispatcher on its own, and
 * nothing else that extracts an entity reaches this loop, so what arrives here is the picture's and
 * nothing else.
 */
@Mixin(LevelExtractor.class)
public abstract class ShadowReuseExtractMixin {

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"), require = 1)
	private void vitrail$lendFrom(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
			LevelRenderState output, CallbackInfo callback) {
		ShadowReuse.frameStart(camera.position());
	}

	@WrapOperation(method = "extractVisibleEntities", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;"
					+ "extractEntity(Lnet/minecraft/world/entity/Entity;F)"
					+ "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private EntityRenderState vitrail$lend(LevelExtractor self, Entity entity, float partial,
			Operation<EntityRenderState> original) {
		EntityRenderState state = original.call(self, entity, partial);
		ShadowReuse.extracted(entity, state, partial);

		return state;
	}
}
