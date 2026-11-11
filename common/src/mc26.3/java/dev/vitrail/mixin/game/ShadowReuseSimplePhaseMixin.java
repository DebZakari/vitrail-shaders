package dev.vitrail.mixin.game;

import dev.vitrail.render.ShadowReuse;

import java.util.Collection;
import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks what a caster files into an unordered phase, and hands the phase's runs on split by the mark.
 * <p>
 * The phase gives its submissions to the grouper a run at a time, one run per feature with no batch
 * and one per batch, and a run becomes one group whole. So a run that holds both kinds is handed on
 * as two, the unmarked then the marked, each in its own order: the phase is unordered, and the game
 * itself shuffles these runs when it checks that nothing depends on their order. Both calls are
 * wrapped, and both have to be: a run handed on whole would build draws of both kinds.
 */
@Mixin(SimpleFeatureRenderPhase.class)
public abstract class ShadowReuseSimplePhaseMixin {

	@Inject(method = "submit", at = @At("HEAD"), require = 1)
	private void vitrail$mark(SubmitNode submit, CallbackInfo callback) {
		ShadowReuse.submitted(submit);
	}

	@WrapOperation(method = "sortFeatureInto", require = 2, allow = 2,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/phase/"
					+ "FeatureRenderPhase$Output;acceptFeatureGroup("
					+ "Lnet/minecraft/client/renderer/feature/FeatureRendererType;Ljava/util/Collection;Z)V"))
	private static void vitrail$apart(FeatureRenderPhase.Output output, FeatureRendererType<?> type,
			Collection<?> submits, boolean strictlyOrdered, Operation<Void> original) {
		List<? extends List<?>> runs = ShadowReuse.runs(submits);
		if (runs == null) {
			original.call(output, type, submits, strictlyOrdered);
			return;
		}

		for (List<?> run : runs) {
			original.call(output, type, run, strictlyOrdered);
		}
	}
}
