package dev.vitrail.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * One colour target of a pack: its texture, the view a sampler reads it whole through, and the
 * view of its base level alone for a compute to store into.
 * <p>
 * This exists because {@link com.mojang.blaze3d.pipeline.TextureTarget} cannot express a mip chain.
 * {@code RenderTarget.createBuffers} calls {@code createTexture} with a level count of one, hard
 * coded, and offers no way to ask for more; a target a pack reads at a lod therefore cannot be one.
 * Everything else it did for us was one texture, one view and a resize, which is what is here.
 * <p>
 * The level count is a property of the target rather than of a program: several programs may read
 * the same target at a lod, and the chain that serves them is one. It runs to one texel on the
 * LONGER side, {@code log2(max(width, height)) + 1}, which is the chain OpenGL builds and the one
 * every pack reading a lod was written against. The shorter side floors at one texel along the
 * way, and the blit that fills the chain floors it too; {@link GpuTexture#getWidth} does not, so
 * a level of that tail has to be sized by hand wherever its extent is wanted.
 */
final class TargetSurface implements AutoCloseable {

	/** Copied from what the game asks for its own targets: sampled, drawn into, and copied both ways. */
	private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
			| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

	private final String label;
	private final GpuFormat format;
	private final boolean mipped;

	/** Whether a compute of the pack writes this target as an image, which is a usage bit at creation. */
	private final boolean storage;

	/**
	 * Whether {@link MipmapCompute} fills this chain, which takes storage usage and a view of each
	 * level. Settled at each allocation: the device's answer for the format does not change, but
	 * a resize can take a chain from one level to several.
	 */
	private boolean mipStorage;

	/** The dispatches that fill the chain, fixed by its size. Empty unless {@link #mipStorage}. */
	private List<MipmapCompute.Step> mipPlan = List.of();

	private GpuTexture texture;
	private GpuTextureView view;

	/**
	 * The base level alone, for storage descriptors and render attachments. Null without a chain, where
	 * {@link #view} is one level already.
	 */
	private GpuTextureView baseView;

	/**
	 * Each level alone, for the compute that fills the chain; the first is {@link #baseView}. Null
	 * unless {@link #mipStorage}.
	 */
	private GpuTextureView[] levelViews;

	private int width;
	private int height;

	/**
	 * Whether anything has ever written the levels past the base of THIS texture.
	 * <p>
	 * The one thing that decides whether a lod read is safe. A fresh texture's levels hold whatever
	 * the driver left there, so a sampler allowed to climb the chain before the reduction has run
	 * once serves undefined memory rather than a coarser image, which is the defect 4d52d20 closed
	 * for the shadow map. Stale is a different matter and not a danger: a chain built two passes ago
	 * is a real image of the target, only an older one, and the walk that fills chains runs the
	 * reduction before any program that reads one where something has written the base since the
	 * last fill.
	 */
	private boolean chainWritten;

	/**
	 * @param mipped whether this target is read at a lod by any program of the place, which is what
	 *               decides that it costs a chain. A target nothing samples that way carries one
	 *               level and one view, exactly as before there were chains at all
	 */
	TargetSurface(String label, GpuFormat format, boolean mipped, int width, int height) {
		this(label, format, mipped, false, width, height);
	}

	/**
	 * @param storage whether a compute of the pack writes this target as a storage image, which
	 *                has to be said at creation: the usage is baked into the image
	 */
	TargetSurface(String label, GpuFormat format, boolean mipped, boolean storage, int width,
			int height) {
		this.label = label;
		this.format = format;
		this.mipped = mipped;
		this.storage = storage;
		allocate(width, height);
	}

	/**
	 * The chain runs until the LONGER side is one texel, and is one level when nothing reads a lod.
	 * <p>
	 * The longer side and not the shorter, which is what OpenGL's full chain is and therefore what
	 * every pack reading a lod was written against: on a 2560 by 1440 screen a chain counted off
	 * the shorter side stops at 2 by 1, and a lod read past that level is clamped to it where
	 * OpenGL had one more. The shorter side floors at one texel along the way, as it does there.
	 */
	static int levelsFor(boolean mipped, int width, int height) {
		return mipped ? Mth.log2(Math.max(width, height)) + 1 : 1;
	}

	int width() {
		return this.width;
	}

	int height() {
		return this.height;
	}

	int levels() {
		return this.texture == null ? 0 : this.texture.getMipLevels();
	}

	GpuTexture texture() {
		return this.texture;
	}

	/** Whether the levels past the base have been written since this texture was allocated. */
	boolean chainWritten() {
		return this.chainWritten;
	}

	/** Said by the reduction once it has filled every level, and by nothing else. */
	void chainWritten(boolean written) {
		this.chainWritten = written;
	}

	/** What level nought costs, which is what this surface would have cost with no chain at all. */
	long baseBytes() {
		return (long) this.width * this.height * this.format.blockSize();
	}

	/**
	 * What the whole surface costs, chain included. Summed level by level rather than taken as the
	 * four thirds a full chain tends towards: the levels floor at one texel, so the tail of a target
	 * that is not square is heavier than the ratio suggests.
	 */
	long bytes() {
		long texels = 0L;
		for (int level = 0; level < levels(); level++) {
			texels += (long) Math.max(1, this.width >> level) * Math.max(1, this.height >> level);
		}

		return texels * this.format.blockSize();
	}

	/** The whole chain, which is what a sampler binds: a lod read past the base needs the levels in view. */
	GpuTextureView view() {
		return this.view;
	}

	/**
	 * The base level alone for storage descriptors and render attachments: Vulkan requires one level
	 * in either role, and a pack program writes level nought. The same object as {@link #view}
	 * on a surface with no chain, where the whole view is one level already.
	 */
	GpuTextureView storageView() {
		return this.baseView == null ? this.view : this.baseView;
	}

	/** Whether this surface was created writable as a storage image, which cannot be added later. */
	boolean storage() {
		return this.storage;
	}

	/** Whether this chain was allocated for {@link MipmapCompute}: storage usage and a view per level. */
	boolean mipStorage() {
		return this.mipStorage;
	}

	GpuFormat format() {
		return this.format;
	}

	/** The dispatches {@link MipmapCompute} fills this chain in, planned once per allocation. */
	List<MipmapCompute.Step> mipPlan() {
		return this.mipPlan;
	}

	/** One level alone. Only on a surface allocated for {@link MipmapCompute}. */
	GpuTextureView levelView(int level) {
		return this.levelViews[level];
	}

	/**
	 * Makes this surface exist at the given size, reallocating when it moved.
	 *
	 * @return true when anything was allocated, which is the caller's signal that a clear is owed
	 */
	boolean resize(int width, int height) {
		if (this.texture != null && this.width == width && this.height == height) {
			return false;
		}

		close();
		allocate(width, height);

		return true;
	}

	private void allocate(int width, int height) {
		GpuDevice device = RenderSystem.getDevice();
		int levels = levelsFor(this.mipped, width, height);

		this.width = width;
		this.height = height;
		// A new image, so its levels are whatever the driver left there until the reduction says
		// otherwise. Set before anything can read it rather than after, because a resize is exactly
		// the moment a chain silently stops being true.
		this.chainWritten = false;
		this.mipStorage = levels > 1 && MipmapCompute.serves(this.format);
		this.mipPlan = this.mipStorage ? MipmapCompute.plan(width, height, levels) : List.of();

		// Everything or nothing. A view that throws after the texture was made would leave that
		// texture with no owner: the caller only learns of a surface once the constructor returns,
		// so a half built one is never put in a map, never closed, and not even a resource reload
		// gets it back. The chain makes this real rather than theoretical, since one surface now
		// creates up to a dozen views instead of one.
		try {
			// The flag is read by the mixin on the game's usage conversion, inside this one call,
			// and lowered whatever the call did: a throw that left it up would mark the next
			// texture anybody creates.
			TextureUsage.requestStorage(this.storage || this.mipStorage);
			try {
				this.texture = device.createTexture(this.label, USAGE, this.format, width, height, 1,
						levels);
			} finally {
				TextureUsage.requestStorage(false);
			}

			this.view = device.createTextureView(this.texture);

			if (levels > 1) {
				this.baseView = device.createTextureView(this.texture, 0, 1);
			}

			if (this.mipStorage) {
				this.levelViews = new GpuTextureView[levels];
				this.levelViews[0] = this.baseView;
				for (int level = 1; level < levels; level++) {
					this.levelViews[level] = device.createTextureView(this.texture, level, 1);
				}
			}
		} catch (RuntimeException e) {
			close();
			throw e;
		}
	}

	/**
	 * Frees the texture and every view onto it. The views go first: closing a texture does not close
	 * the views onto it, and nothing on the Vulkan backend checks that a bound view is still alive.
	 */
	@Override
	public void close() {
		if (this.levelViews != null) {
			// The first is the base view, closed below.
			for (int level = 1; level < this.levelViews.length; level++) {
				if (this.levelViews[level] != null) {
					this.levelViews[level].close();
				}
			}

			this.levelViews = null;
		}

		if (this.baseView != null) {
			this.baseView.close();
			this.baseView = null;
		}

		if (this.view != null) {
			this.view.close();
			this.view = null;
		}

		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
	}
}
