package dev.vitrail.mixin.game;

import dev.vitrail.render.ShadowReuse;

import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks what a caster files into an ordered phase. Its runs are single submissions, kept in their
 * order by distance, so the grouper alone keeps the kinds apart there.
 * <p>
 * On the method taking the translucent submission and not on the bridge the compiler adds for the
 * interface, which only forwards to it.
 */
@Mixin(TranslucentFeatureRenderPhase.class)
public abstract class ShadowReuseTranslucentPhaseMixin {

	@Inject(method = "submit(Lnet/minecraft/client/renderer/feature/submit/TranslucentSubmit;)V",
			at = @At("HEAD"), require = 1)
	private void vitrail$mark(TranslucentSubmit submit, CallbackInfo callback) {
		ShadowReuse.submitted(submit);
	}
}
