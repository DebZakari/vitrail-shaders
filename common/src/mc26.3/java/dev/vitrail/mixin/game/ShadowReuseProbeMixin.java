package dev.vitrail.mixin.game;

import dev.vitrail.render.ShadowGeometry;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spike probe: records the entities the main pass extracts this frame. */
@Mixin(LevelExtractor.class)
public abstract class ShadowReuseProbeMixin {

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void vitrail$probeClear(CallbackInfo ci) {
		ShadowGeometry.frameStart();
	}

	@WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private EntityRenderState vitrail$probeKeep(LevelExtractor self, Entity entity, float partial,
			Operation<EntityRenderState> original) {
		ShadowGeometry.MAIN_PASS.add(entity);
		return original.call(self, entity, partial);
	}
}
