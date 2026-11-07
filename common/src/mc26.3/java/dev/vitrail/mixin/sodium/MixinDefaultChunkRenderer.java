package dev.vitrail.mixin.sodium;

import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.LevelPass;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.render.timing.RingTimings;
import dev.vitrail.sodium.SodiumApi;
import dev.vitrail.sodium.SodiumPasses;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jspecify.annotations.Nullable;

/**
 * Draws the chunk renderer's terrain into a pass with the colour targets the pack's program writes,
 * instead of the single one Sodium was going to draw it into.
 * <p>
 * Sodium's pass is a one attachment pass on the game's main target. A {@code gbuffers_terrain}
 * declaring {@code DRAWBUFFERS:0,6,4} therefore has nowhere to put its second and third outputs, and
 * they are written nowhere at all, which is what the log had been saying since the first step of
 * milestone six.
 * <p>
 * Replacing that pass is the whole hook. Everything else stays Sodium's: the draw commands, the
 * regions, the culling, the push constants. Rewriting any of that is out of the question, it is the
 * most internal code Sodium has and it is under a licence this project cannot copy from.
 * <p>
 * <strong>Draw buffer nought comes here too, and the sky and the entities still do not.</strong>
 * What a {@code gbuffers_terrain} puts there is not a colour but whatever the pack packed there, and
 * the game's own target is eight bits a channel: a pack encoding two values per channel loses one of
 * them on the way through. So the pass writes the pack's target outright and marks the pixels it
 * covered, and the scene seed brings the rest of the game's picture in around them.
 * <p>
 * The depth view is passed through untouched. The terrain has to depth test against the sky the game
 * has already drawn, and the entities, the particles and the hand have to test against the terrain.
 * <p>
 * <strong>The 26.3 half</strong>, against Sodium 0.9.2 for that game, which follows the game in
 * handing the render pass to the renderer rather than letting it open one. Three things moved.
 * <ul>
 * <li>{@code render} takes the pass, the level's own one ({@link LevelPass}) on the camera's road
 * and, for the light's walk, the one {@link SodiumApi} opened on the shadow map, beside an order
 * independent stage that is null on the classic road. The pass is replaced at its first use, which
 * is past Sodium's test for anything to draw and past {@code begin}, where the pipeline is compiled:
 * the point Sodium 0.9.2 for 26.2 opened its pass at. The descriptor is asked as it was, of the
 * pass's target, and the pack's pass is opened as it was, through {@link GeometryHold}, which steps
 * the level's pass aside; on the light's walk it joins the pass already open on the map wherever the
 * descriptor names the same images, and opens another where a pack gave two of its shadow programs
 * different draw buffers, which is what the pass per terrain pass of 26.2 did. It is closed where
 * {@code render} returns, as Sodium closed its own. A stage, which is set only while no pack draws,
 * and a pass other than those two, whose images this cannot vouch for, are left as they come.</li>
 * <li>The draw commands are picked in {@code prepare}, for every terrain pass at once, rather than
 * inside {@code render}, so the block face culling the light's walk turns off is read there. Sodium
 * calls it from the level for the camera, and {@link SodiumApi} calls it inside the light's walk,
 * which is what fills the shadow's own batches at all.</li>
 * <li>The target is no longer asked for inside {@code render}, and when it is asked it is the main
 * target outright rather than a resource of the frame graph, so there is nothing left for the shadow
 * draw to trip over and the wrap that answered it for the light's walk is gone.</li>
 * </ul>
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer {

	/** The pass opened for one terrain pass, or null where the renderer kept the one handed in. */
	@Unique
	private @Nullable RenderPass vitrail$opened;

	// require, because a silently unapplied probe prints the same zeros as an empty rotate, and
	// the whole point of the clock is telling those two apart.
	@Inject(method = "rotate", at = @At("HEAD"), require = 1)
	private void vitrail$rotateBegin(CallbackInfo ci) {
		RingTimings.beginRotate();
	}

	@Inject(method = "rotate", at = @At("RETURN"), require = 1)
	private void vitrail$rotateEnd(CallbackInfo ci) {
		RingTimings.endRotate();
	}

	/**
	 * Serves every face of a section while the shadow map is drawn.
	 * <p>
	 * The batches Sodium builds leave out the faces that point away from the camera, and the light
	 * is not the camera: a face the player cannot see still stands between the sun and the ground.
	 * The pipeline of the shadow passes already draws both sides; this reaches the culling that
	 * happens before any pipeline, when the draw commands are picked. Iris turns the same option
	 * off at the same point for the same reason.
	 */
	@WrapOperation(
			method = "prepare",
			at = @At(value = "FIELD",
					target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;"
							+ "useBlockFaceCulling:Z"))
	private boolean vitrail$shadowFaces(SodiumOptions.PerformanceSettings settings,
			Operation<Boolean> original) {
		return !TerrainDraw.drawingShadow() && original.call(settings);
	}

	/**
	 * Hands the renderer the pass the pack's program writes, instead of the one it was handed.
	 */
	@ModifyVariable(method = "render", require = 1, argsOnly = true,
			at = @At(value = "LOAD", ordinal = 0))
	private RenderPass vitrail$pass(RenderPass pass, ChunkRenderMatrices matrices,
			ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
			CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled,
			RenderPass handed, GpuSampler terrainSampler, GpuBufferSlice uniformData,
			GpuBuffer sectionTimeInfo, @Nullable OitStage stage) {
		this.vitrail$opened = null;
		if (stage != null || !(pass instanceof LevelPass || TerrainDraw.drawingShadow())) {
			return pass;
		}

		TerrainPass ours = SodiumPasses.of(renderPass);
		RenderTarget target = renderPass.getTarget();
		RenderPassDescriptor descriptor = ours == null
				? null
				: TerrainDraw.descriptor(ours, target.getColorTextureView(),
						target.getDepthTextureView());
		if (descriptor == null) {
			return pass;
		}

		this.vitrail$opened = GeometryHold.open(RenderSystem.getDevice().createCommandEncoder(),
				descriptor);

		return this.vitrail$opened;
	}

	/**
	 * The precision Sodium rounded the camera's fraction to before it moved it out of the region
	 * offset: a region's width in blocks, so that adding and subtracting any offset inside one region
	 * gives back the same position and neighbouring regions meet without a seam.
	 */
	@Unique
	private static final float VITRAIL$FRACTION_PRECISION = RenderRegion.REGION_WIDTH * 16;

	/**
	 * Puts the camera's place inside its block back into the region offset, on the Sodium builds
	 * that took it out.
	 * <p>
	 * Up to 0.9.3-beta.1 the offset Sodium pushes for each region is the region's origin minus the
	 * camera, fraction included, and our chunk programs build the vertex from it alone
	 * ({@code SodiumVertex}). From 0.9.3-beta.2 the offset is whole blocks, {@code
	 * getCameraTranslation(int, int)}, and Sodium's own shader subtracts the fraction afterwards
	 * from a {@code u_CameraOffset} in its {@code u_Globals}, a block our programs never declare.
	 * Read as before, every vertex lands up to a block away from where the camera expects it, and
	 * slides as the player walks, in the shadow map as much as in the world.
	 * <p>
	 * Only while one of our pipelines is bound, which the draw context's bind has just settled:
	 * Sodium's shader reads its own offset and must keep it whole. The fraction is rounded as Sodium
	 * rounded it while it was still pushed, so a frame is drawn from the same offsets either way.
	 * {@code require = 0} because the call only exists on those builds: on the earlier ones there is
	 * nothing to match, and nothing to put back.
	 */
	@ModifyExpressionValue(method = "render", require = 0, allow = 1,
			at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/caffeinemc/mods/sodium/client/"
					+ "render/chunk/DefaultChunkRenderer;getCameraTranslation(II)F"))
	private float vitrail$offsetX(float offset, @Local(argsOnly = true) CameraTransform camera) {
		return TerrainDraw.boundOurs() ? offset - vitrail$fraction(camera.x, camera.intX) : offset;
	}

	@ModifyExpressionValue(method = "render", require = 0, allow = 1,
			at = @At(value = "INVOKE", ordinal = 1, target = "Lnet/caffeinemc/mods/sodium/client/"
					+ "render/chunk/DefaultChunkRenderer;getCameraTranslation(II)F"))
	private float vitrail$offsetY(float offset, @Local(argsOnly = true) CameraTransform camera) {
		return TerrainDraw.boundOurs() ? offset - vitrail$fraction(camera.y, camera.intY) : offset;
	}

	@ModifyExpressionValue(method = "render", require = 0, allow = 1,
			at = @At(value = "INVOKE", ordinal = 2, target = "Lnet/caffeinemc/mods/sodium/client/"
					+ "render/chunk/DefaultChunkRenderer;getCameraTranslation(II)F"))
	private float vitrail$offsetZ(float offset, @Local(argsOnly = true) CameraTransform camera) {
		return TerrainDraw.boundOurs() ? offset - vitrail$fraction(camera.z, camera.intZ) : offset;
	}

	/** The camera's fraction on one axis, at the precision the region offset carried it. */
	@Unique
	private static float vitrail$fraction(double position, int whole) {
		float fraction = (float) (position - whole);
		float modifier = Math.copySign(VITRAIL$FRACTION_PRECISION, fraction);

		return (fraction + modifier) - modifier;
	}

	/**
	 * Closes the pass opened above where the renderer returns, as Sodium closed its own.
	 * {@link GeometryHold} keeps it open for the next terrain pass that writes the same images.
	 */
	@Inject(method = "render", at = @At("RETURN"), require = 1)
	private void vitrail$close(CallbackInfo callback) {
		RenderPass opened = this.vitrail$opened;
		this.vitrail$opened = null;
		if (opened != null) {
			opened.close();
		}
	}
}
