package dev.vitrail.mixin.game;

import dev.vitrail.render.PackChain;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps Fabric Renderer API's extended block-crack submission in the same overlay as vanilla.
 * Fabric replaces LevelRenderer's vanilla call with a mesh-carrying overload, so correcting only
 * the vanilla method never reaches the normal Fabric rendering path. Preserve the extended mesh
 * and all its render state; only change the phase selected for an opaque block while a pack draws.
 */
@Mixin(value = SubmitNodeCollection.class, priority = 1100)
public abstract class SubmitNodeCollectionFabricCrumblingMixin {

	// Fabric merges this method at priority 1000. A higher priority permits injecting into its
	// merged method. The descriptor is optional because Renderer API need not be installed, and
	// NeoForge has no such method. The offline Fabric integration audit requires this hook when
	// Renderer API is present, rather than treating a successful vanilla injection as coverage.
	@Dynamic("Added by Fabric Renderer API's SubmitNodeCollectionMixin")
	@ModifyExpressionValue(
			method = "submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;"
					+ "Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/Mesh;IZ)V",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;"
					+ "solid:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;"),
			require = 0, remap = false)
	private SimpleFeatureRenderPhase vitrail$fabricBreakingBlock(SimpleFeatureRenderPhase chosen) {
		return PackChain.drawingPack() ? ((SubmitNodeCollection) (Object) this).breakingOverlay : chosen;
	}
}
