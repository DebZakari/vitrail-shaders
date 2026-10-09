package dev.vitrail.render;

import dev.vitrail.glsl.LoadClock;
import dev.vitrail.mixin.access.CommandEncoderAccessor;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.mixin.access.VulkanCommandEncoderAccessor;
import dev.vitrail.render.storage.GpuRecording;
import dev.vitrail.render.timing.PassBarrier;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSpecializationInfo;
import org.lwjgl.vulkan.VkSpecializationMapEntry;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fills the mip chain of a colour target by compute, in a few dispatches instead of a blit per level.
 * <p>
 * A blit per level is one barrier per level, and each barrier drains the GPU: at 1080p a chain is
 * ten blits, the first at the bandwidth floor and the nine after it mostly waiting. Here the levels
 * that halve exactly are built together, one workgroup per 64 by 64 tile of the level above them,
 * reducing through shared memory the way FidelityFX's single-pass downsampler does. A level that
 * does not halve exactly, an odd extent, takes a dispatch of its own, and once a level is small
 * one workgroup builds it and every level after it.
 * <p>
 * The values are the blit's: the hardware linear filter at the centre of each texel written,
 * scaled onto the level above, which on a level that halves exactly is the average of the four
 * texels under it. A level built from one the same dispatch built is filtered by hand from shared
 * memory, with the same weights. What differs is rounding: such a level is filtered from the values
 * of the one above before they are stored, rather than after, so a texel can land one step of its
 * format away from the blit's.
 * <p>
 * Only for a colour format the device stores into, filters linearly and that has a GLSL storage
 * qualifier, and only on NVIDIA, the one vendor where storage usage was measured to cost the target
 * nothing; anything else keeps the blits, as does a chain recorded with a pass open. The surface
 * has to have asked for storage usage and per-level views at allocation, which
 * {@link TargetSurface} does through {@link #serves}.
 */
final class MipmapCompute {

	static final int TILE = 0;
	static final int SINGLE = 1;
	static final int TAIL = 2;

	/** Invocations per workgroup, in every mode. */
	static final int GROUP = 256;

	/** Levels one tile builds at most: a 64 by 64 tile of the level above reduces to one texel. */
	static final int TILE_LEVELS = 6;

	/**
	 * The tail starts at the first level of this many texels or fewer, built by one workgroup in
	 * shared memory: 16 KiB of {@code vec4}, the most every device gives a workgroup. Four per
	 * invocation, so the level after it is two per invocation at most.
	 */
	static final int TAIL_TEXELS = 1024;

	/** Levels one tail dispatch writes at most; a longer tail takes another dispatch. */
	static final int TAIL_LEVELS = 7;

	/** One storage binding per level written. */
	private static final int STORAGE_BINDINGS = TAIL_LEVELS;

	private static final int PUSH_CONSTANT_BYTES = 16;

	static final String SOURCE = """
			#version 450

			layout(local_size_x = 256) in;

			// The level above the first one written, alone, through a clamped linear sampler.
			layout(binding = 0) uniform sampler2D source;
			// Each level this dispatch writes, in order. Only ever stored to: a level that another
			// in the same dispatch is built from is read back from shared memory.
			layout(binding = 1, FORMAT) uniform writeonly image2D level1;
			layout(binding = 2, FORMAT) uniform writeonly image2D level2;
			layout(binding = 3, FORMAT) uniform writeonly image2D level3;
			layout(binding = 4, FORMAT) uniform writeonly image2D level4;
			layout(binding = 5, FORMAT) uniform writeonly image2D level5;
			layout(binding = 6, FORMAT) uniform writeonly image2D level6;
			layout(binding = 7, FORMAT) uniform writeonly image2D level7;

			layout(push_constant) uniform Chain {
				ivec2 size;  // of the level source holds, level 0 here
				int mode;    // 0 tile, 1 single, 2 tail
				int count;   // levels written
			} chain;

			// A tile's 16 by 16 texels of level 2, or a whole tail level. Sized per pipeline, so the
			// dispatches over the large levels do not reserve the tail's 16 KiB.
			layout(constant_id = 0) const int CELLS = TAIL_TEXELS;
			shared vec4 cells[CELLS];

			// The extent of a level, which floors at one texel the way the image's own levels do.
			ivec2 sizeOf(int level) {
				return max(chain.size >> level, ivec2(1));
			}

			void store(int level, ivec2 texel, vec4 value) {
				switch (level) {
				case 1: imageStore(level1, texel, value); break;
				case 2: imageStore(level2, texel, value); break;
				case 3: imageStore(level3, texel, value); break;
				case 4: imageStore(level4, texel, value); break;
				case 5: imageStore(level5, texel, value); break;
				case 6: imageStore(level6, texel, value); break;
				case 7: imageStore(level7, texel, value); break;
				}
			}

			// Levels that each halve the one above exactly, from one 64 by 64 tile of level 0: each
			// invocation filters a 2 by 2 block of level 1 and averages it into one texel of level
			// 2, and shared memory carries the 16 by 16 texels of level 2 down from there. Exact
			// halving is what keeps a tile to itself: every texel inside the image reads only
			// texels inside it, so the ones a partial tile computes past the edge are never read.
			void tile() {
				uint index = gl_LocalInvocationIndex;
				ivec2 group = ivec2(gl_WorkGroupID.xy);
				ivec2 below = group * 16 + ivec2(index & 15u, index >> 4u);
				vec2 texel = 1.0 / vec2(chain.size);
				ivec2 size1 = sizeOf(1);
				vec4 sum = vec4(0.0);
				for (int i = 0; i < 4; i++) {
					ivec2 at = below * 2 + ivec2(i & 1, i >> 1);
					// The corner the four texels under it share: one linear tap is their average.
					vec4 value = textureLod(source, vec2(at * 2 + 1) * texel, 0.0);
					if (all(lessThan(at, size1))) {
						store(1, at, value);
					}
					sum += value;
				}

				if (chain.count < 2) {
					return;
				}

				vec4 value = sum * 0.25;
				if (all(lessThan(below, sizeOf(2)))) {
					store(2, below, value);
				}

				cells[index] = value;
				int span = 16;
				for (int level = 3; level <= chain.count; level++) {
					span >>= 1;
					barrier();
					bool reducing = int(index) < span * span;
					ivec2 cell = ivec2(int(index) % span, int(index) / span);
					if (reducing) {
						int at = cell.y * 32 + cell.x * 2;
						value = 0.25 * (cells[at] + cells[at + 1] + cells[at + 16] + cells[at + 17]);
						ivec2 pos = group * span + cell;
						if (all(lessThan(pos, sizeOf(level)))) {
							store(level, pos, value);
						}
					}
					barrier();
					if (reducing) {
						cells[cell.y * 16 + cell.x] = value;
					}
				}
			}

			// One level that does not halve the one above: the blit's tap, at the centre of the
			// texel written scaled onto level 0.
			void single() {
				uint index = gl_LocalInvocationIndex;
				ivec2 pos = ivec2(gl_WorkGroupID.xy) * 16 + ivec2(index & 15u, index >> 4u);
				ivec2 size1 = sizeOf(1);
				if (all(lessThan(pos, size1))) {
					store(1, pos, textureLod(source, (vec2(pos) + 0.5) / vec2(size1), 0.0));
				}
			}

			// The small levels, all in one workgroup and in shared memory, one level at a time. The
			// first is the tap single() takes; each after it is that tap filtered by hand from the
			// level before, with the weights the linear filter gives. A level after the first has
			// half the texels of the one before it at most, so two per invocation.
			void tail() {
				int index = int(gl_LocalInvocationIndex);
				ivec2 size = sizeOf(1);
				for (int n = index; n < size.x * size.y; n += 256) {
					ivec2 pos = ivec2(n % size.x, n / size.x);
					vec4 value = textureLod(source, (vec2(pos) + 0.5) / vec2(size), 0.0);
					store(1, pos, value);
					cells[n] = value;
				}

				for (int level = 2; level <= chain.count; level++) {
					ivec2 above = size;
					size = sizeOf(level);
					vec2 scale = vec2(above) / vec2(size);
					vec4 values[2];
					barrier();
					for (int k = 0; k < 2; k++) {
						int n = index + k * 256;
						if (n < size.x * size.y) {
							ivec2 pos = ivec2(n % size.x, n / size.x);
							vec2 at = (vec2(pos) + 0.5) * scale - 0.5;
							ivec2 low = ivec2(floor(at));
							vec2 f = at - vec2(low);
							ivec2 lo = clamp(low, ivec2(0), above - 1);
							ivec2 hi = clamp(low + 1, ivec2(0), above - 1);
							vec4 top = mix(cells[lo.y * above.x + lo.x], cells[lo.y * above.x + hi.x], f.x);
							vec4 bottom = mix(cells[hi.y * above.x + lo.x], cells[hi.y * above.x + hi.x], f.x);
							values[k] = mix(top, bottom, f.y);
							store(level, pos, values[k]);
						}
					}

					// Every invocation has read the level above before any overwrites it.
					barrier();
					for (int k = 0; k < 2; k++) {
						int n = index + k * 256;
						if (n < size.x * size.y) {
							cells[n] = values[k];
						}
					}
				}
			}

			void main() {
				if (chain.mode == 0) {
					tile();
				} else if (chain.mode == 1) {
					single();
				} else {
					tail();
				}
			}
			""";

	/** One dispatch: its mode, the first level it writes, and how many it writes. */
	record Step(int mode, int first, int count) {
	}

	/**
	 * One format's two pipelines from the same module: the tile's and single's, whose shared memory
	 * is a tile's 16 by 16 texels, and the tail's, whose is {@link #TAIL_TEXELS}.
	 */
	private record Pipelines(long blocks, long tail) {

		boolean built() {
			return this.blocks != 0L && this.tail != 0L;
		}

		long of(Step step) {
			return step.mode() == TAIL ? this.tail : this.blocks;
		}
	}

	private static final Pipelines FAILED = new Pipelines(0L, 0L);

	/** Pipelines by format, {@link #FAILED} where a format failed to build: it keeps the blits. */
	private static final Map<GpuFormat, Pipelines> PIPELINES = new HashMap<>();

	private static long setLayout;
	private static long pipelineLayout;

	/** NVIDIA's PCI vendor id. */
	private static final int NVIDIA = 0x10DE;

	/** Null until a device was asked, then whether it is one this serves. */
	private static Boolean deviceFits;

	private MipmapCompute() {
	}

	/**
	 * Whether a chain of this format can be filled here, which a surface asks before it allocates:
	 * the answer decides its usage and its views. The pipeline is built here, at allocation, rather
	 * than inside the first frame that fills a chain, and a format whose pipeline fails keeps its
	 * blits, so needs no storage usage.
	 */
	static boolean serves(GpuFormat format) {
		if (qualifier(format) == null || !GpuFormats.storageCapable(format)
				|| !GpuFormats.filtersLinearly(format) || !deviceFits()) {
			return false;
		}

		VulkanDevice vulkan = vulkan();
		return vulkan != null && pipelines(vulkan, format).built();
	}

	/** The dispatches that fill levels 1 to {@code levels - 1} of an image of that size. */
	static List<Step> plan(int width, int height, int levels) {
		List<Step> steps = new ArrayList<>();
		int level = 1;
		while (level < levels) {
			if ((long) extent(width, level) * extent(height, level) <= TAIL_TEXELS) {
				int count = Math.min(levels - level, TAIL_LEVELS);
				steps.add(new Step(TAIL, level, count));
				level += count;
				continue;
			}

			int exact = 0;
			while (exact < TILE_LEVELS && level + exact < levels
					&& halves(width, height, level + exact)) {
				exact++;
			}

			if (exact >= 2) {
				steps.add(new Step(TILE, level, exact));
				level += exact;
			} else {
				steps.add(new Step(SINGLE, level, 1));
				level++;
			}
		}

		return steps;
	}

	/** The extent of a level along one side, floored at one texel as the image's levels are. */
	static int extent(int base, int level) {
		return Math.max(1, base >> level);
	}

	/** Whether a level is exactly half the one above on both sides. */
	private static boolean halves(int width, int height, int level) {
		return extent(width, level - 1) == 2 * extent(width, level)
				&& extent(height, level - 1) == 2 * extent(height, level);
	}

	/** The workgroups a step dispatches, along x and y. */
	static int[] groups(Step step, int width, int height) {
		int x = extent(width, step.first());
		int y = extent(height, step.first());
		return switch (step.mode()) {
			// Each tile covers 32 by 32 texels of the first level it writes.
			case TILE -> new int[] {(x + 31) / 32, (y + 31) / 32};
			case SINGLE -> new int[] {(x + 15) / 16, (y + 15) / 16};
			default -> new int[] {1, 1};
		};
	}

	/** Every storage qualifier a pipeline can be built with, one per format served. */
	static List<String> qualifiers() {
		return Arrays.stream(GpuFormat.values()).map(MipmapCompute::qualifier).filter(Objects::nonNull)
				.toList();
	}

	/** The GLSL storage qualifier of a filterable colour format, or null for one this does not serve. */
	static String qualifier(GpuFormat format) {
		return switch (format) {
			case R8_UNORM -> "r8";
			case R8_SNORM -> "r8_snorm";
			case RG8_UNORM -> "rg8";
			case RG8_SNORM -> "rg8_snorm";
			case RGBA8_UNORM -> "rgba8";
			case RGBA8_SNORM -> "rgba8_snorm";
			case R16_UNORM -> "r16";
			case R16_SNORM -> "r16_snorm";
			case RG16_UNORM -> "rg16";
			case RG16_SNORM -> "rg16_snorm";
			case RGBA16_UNORM -> "rgba16";
			case RGBA16_SNORM -> "rgba16_snorm";
			case R16_FLOAT -> "r16f";
			case RG16_FLOAT -> "rg16f";
			case RGBA16_FLOAT -> "rgba16f";
			case R32_FLOAT -> "r32f";
			case RG32_FLOAT -> "rg32f";
			case RGBA32_FLOAT -> "rgba32f";
			case RGB10A2_UNORM -> "rgb10_a2";
			case RG11B10_FLOAT -> "r11f_g11f_b10f";
			default -> null;
		};
	}

	/**
	 * Fills every level past the base of a surface allocated for it.
	 *
	 * @return false when nothing was recorded, so the caller fills the chain by blits instead
	 */
	static boolean generate(CommandEncoder encoder, TargetSurface surface) {
		if (!surface.mipStorage()
				|| !(((CommandEncoderAccessor) encoder).vitrail$backend() instanceof VulkanCommandEncoder recorder)) {
			return false;
		}

		VulkanCommandEncoderAccessor access = (VulkanCommandEncoderAccessor) recorder;
		VulkanDevice vulkan = vulkan();
		if (access.vitrail$currentRenderPass() != null || vulkan == null) {
			return false;
		}

		Pipelines pipelines = pipelines(vulkan, surface.format());
		if (!pipelines.built()) {
			return false;
		}

		int width = surface.width();
		int height = surface.height();
		List<Step> steps = surface.mipPlan();
		long sampler = ((VulkanGpuSampler) PackPass.sampler(false, FilterMode.LINEAR, false)).vkSampler();
		// The wide wait, when armed, stands after every dispatch as it stands after every blit.
		boolean wide = PassBarrier.full();
		VkCommandBuffer commands = access.vitrail$commandBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long bound = 0L;
			for (int s = 0; s < steps.size(); s++) {
				Step step = steps.get(s);
				if (s > 0) {
					if (wide) {
						VulkanCommandEncoder.memoryBarrier(commands, stack);
					} else {
						betweenSteps(commands, stack);
					}
				}

				long pipeline = pipelines.of(step);
				if (pipeline != bound) {
					VK12.vkCmdBindPipeline(commands, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
					bound = pipeline;
				}

				pushDescriptors(commands, stack, surface, step, sampler);
				int above = step.first() - 1;
				VK12.vkCmdPushConstants(commands, pipelineLayout, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0,
						stack.ints(extent(width, above), extent(height, above), step.mode(), step.count()));
				int[] groups = groups(step, width, height);
				VK12.vkCmdDispatch(commands, groups[0], groups[1], 1);
			}

			if (wide) {
				VulkanCommandEncoder.memoryBarrier(commands, stack);
			} else {
				chainTail(commands, stack);
			}
		}

		return true;
	}

	private static void pushDescriptors(VkCommandBuffer commands, MemoryStack stack,
			TargetSurface surface, Step step, long sampler) {
		int above = step.first() - 1;
		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1 + STORAGE_BINDINGS, stack);
		VkDescriptorImageInfo.Buffer sampled = VkDescriptorImageInfo.calloc(1, stack)
				.sampler(sampler)
				.imageView(handle(surface.levelView(above)))
				.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
		writes.get(0).sType$Default()
				.dstBinding(0)
				.descriptorCount(1)
				.descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(sampled);
		for (int i = 0; i < STORAGE_BINDINGS; i++) {
			// A binding past the levels this step writes still has to hold a valid view, the shader
			// naming it statically; the first level written is one, and nothing stores there twice.
			int level = step.first() + (i < step.count() ? i : 0);
			VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack)
					.imageView(handle(surface.levelView(level)))
					.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
			writes.get(1 + i).sType$Default()
					.dstBinding(1 + i)
					.descriptorCount(1)
					.descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
					.pImageInfo(image);
		}

		KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commands, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
				pipelineLayout, 0, writes);
	}

	private static long handle(GpuTextureView view) {
		return ((VulkanGpuTextureView) view).vkImageView();
	}

	/** The last level one dispatch stored becomes what the next one samples. */
	private static void betweenSteps(VkCommandBuffer commands, MemoryStack stack) {
		VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR)
				.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR)
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands,
				VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
	}

	/**
	 * What the filled chain owes the rest of the frame, the blit road's tail barrier with the
	 * compute's stores as its source: a sampled read from any shader stage, a pass writing the base
	 * again, a transfer, or the next fill of this chain storing over these levels.
	 */
	private static void chainTail(VkCommandBuffer commands, MemoryStack stack) {
		VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR)
				.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands,
				VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
	}

	private static VulkanDevice vulkan() {
		GpuDevice device = RenderSystem.tryGetDevice();

		return device != null && ((GpuDeviceAccessor) device).vitrail$backend() instanceof VulkanDevice vulkan
				? vulkan
				: null;
	}

	/**
	 * Whether the device binds as many storage images and runs as wide a workgroup as this asks, and
	 * is one where storage usage was measured to cost a target nothing. Elsewhere it may not: a
	 * driver can turn colour compression off for an image that may be stored to, as some AMD and
	 * Intel parts do, and every pass that writes or samples the target would pay for that.
	 */
	private static boolean deviceFits() {
		if (deviceFits == null) {
			VulkanDevice vulkan = vulkan();
			if (vulkan == null) {
				return false;
			}

			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
				VK10.vkGetPhysicalDeviceProperties(vulkan.vkDevice().getPhysicalDevice(), properties);
				VkPhysicalDeviceLimits limits = properties.limits();
				deviceFits = properties.vendorID() == NVIDIA
						&& limits.maxPerStageDescriptorStorageImages() >= STORAGE_BINDINGS
						&& limits.maxComputeWorkGroupInvocations() >= GROUP
						&& limits.maxComputeWorkGroupSize(0) >= GROUP;
			}
		}

		return deviceFits;
	}

	/**
	 * The pipelines for one format, built on first use and kept until the client shuts down: a few
	 * handles per format a pack mips, against a reload rebuilding them for nothing.
	 */
	private static Pipelines pipelines(VulkanDevice vulkan, GpuFormat format) {
		Pipelines built = PIPELINES.get(format);
		if (built != null) {
			return built;
		}

		Pipelines pipelines = FAILED;
		try {
			pipelines = build(vulkan, format);
		} catch (GpuDeviceLossException e) {
			throw e;
		} catch (RuntimeException e) {
			Vitrail.logger().warn("mip chain compute for {} failed, its chains stay blits: {}", format,
					e.toString());
		}

		PIPELINES.put(format, pipelines);
		if (pipelines.built()) {
			Vitrail.logger().info("Compiled the mip chain compute for {}", format);
		}

		return pipelines;
	}

	private static Pipelines build(VulkanDevice vulkan, GpuFormat format) {
		// Clocked as module work, as the pack's own computes are.
		long began = System.nanoTime();
		ByteBuffer spirv;
		try {
			spirv = compile(source(qualifier(format)));
		} finally {
			LoadClock.module(System.nanoTime() - began);
		}

		if (spirv == null) {
			return FAILED;
		}

		long module = 0L;
		long blocks = 0L;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (setLayout == 0L) {
				createLayouts(vulkan, stack);
			}

			LongBuffer handle = stack.callocLong(1);
			check(VK10.vkCreateShaderModule(vulkan.vkDevice(),
					VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, handle),
					"shader module");
			module = handle.get(0);
			blocks = create(vulkan, stack, module, GROUP);

			return new Pipelines(blocks, create(vulkan, stack, module, TAIL_TEXELS));
		} catch (RuntimeException e) {
			// Never recorded yet, so nothing in flight holds it.
			if (blocks != 0L) {
				VK10.vkDestroyPipeline(vulkan.vkDevice(), blocks, null);
			}

			throw e;
		} finally {
			// A module is only read while the pipelines are created.
			if (module != 0L) {
				VK10.vkDestroyShaderModule(vulkan.vkDevice(), module, null);
			}

			MemoryUtil.memFree(spirv);
		}
	}

	/** One pipeline of the module, its shared memory sized to {@code cells} texels. */
	private static long create(VulkanDevice vulkan, MemoryStack stack, long module, int cells) {
		VkSpecializationMapEntry.Buffer entry = VkSpecializationMapEntry.calloc(1, stack)
				.constantID(0)
				.offset(0)
				.size(Integer.BYTES);
		VkSpecializationInfo specialization = VkSpecializationInfo.calloc(stack)
				.pMapEntries(entry)
				.pData(MemoryUtil.memByteBuffer(stack.ints(cells)));
		VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
		info.get(0).sType$Default().layout(pipelineLayout).stage()
				.sType$Default()
				.stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
				.module(module)
				.pName(stack.UTF8("main"))
				.pSpecializationInfo(specialization);
		LongBuffer handle = stack.callocLong(1);
		check(VK10.vkCreateComputePipelines(vulkan.vkDevice(), 0L, info, null, handle), "pipeline");

		return handle.get(0);
	}

	/** Called when the client shuts down, while the device is still alive. */
	static void close() {
		List<Pipelines> pipelines = List.copyOf(PIPELINES.values());
		long set = setLayout;
		long layout = pipelineLayout;
		PIPELINES.clear();
		setLayout = 0L;
		pipelineLayout = 0L;
		deviceFits = null;
		VulkanDevice vulkan = vulkan();
		if (vulkan == null) {
			return;
		}

		// Deferred like every destruction of this engine's: the last frames may still dispatch them.
		GpuRecording.destroyLater(() -> {
			for (Pipelines built : pipelines) {
				for (long pipeline : new long[] {built.blocks(), built.tail()}) {
					if (pipeline != 0L) {
						VK10.vkDestroyPipeline(vulkan.vkDevice(), pipeline, null);
					}
				}
			}

			if (layout != 0L) {
				VK10.vkDestroyPipelineLayout(vulkan.vkDevice(), layout, null);
			}

			if (set != 0L) {
				VK10.vkDestroyDescriptorSetLayout(vulkan.vkDevice(), set, null);
			}
		});
	}

	private static void createLayouts(VulkanDevice vulkan, MemoryStack stack) {
		VkDescriptorSetLayoutBinding.Buffer bindings =
				VkDescriptorSetLayoutBinding.calloc(1 + STORAGE_BINDINGS, stack);
		for (int i = 0; i <= STORAGE_BINDINGS; i++) {
			bindings.get(i)
					.binding(i)
					.descriptorType(i == 0
							? VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
							: VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
					.descriptorCount(1)
					.stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
		}

		LongBuffer handle = stack.callocLong(1);
		check(VK10.vkCreateDescriptorSetLayout(vulkan.vkDevice(),
				VkDescriptorSetLayoutCreateInfo.calloc(stack)
						.sType$Default()
						.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
						.pBindings(bindings),
				null, handle), "set layout");
		long set = handle.get(0);
		VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
				.stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
				.offset(0)
				.size(PUSH_CONSTANT_BYTES);
		int result = VK10.vkCreatePipelineLayout(vulkan.vkDevice(),
				VkPipelineLayoutCreateInfo.calloc(stack)
						.sType$Default()
						.pSetLayouts(stack.longs(set))
						.pPushConstantRanges(range),
				null, handle);
		if (result != VK10.VK_SUCCESS) {
			VK10.vkDestroyDescriptorSetLayout(vulkan.vkDevice(), set, null);
			check(result, "pipeline layout");
		}

		setLayout = set;
		pipelineLayout = handle.get(0);
	}

	private static void check(int result, String what) {
		if (result != VK10.VK_SUCCESS) {
			throw new IllegalStateException("vkCreate " + what + " answered " + result);
		}
	}

	/** The shader for one storage qualifier. */
	static String source(String qualifier) {
		return SOURCE.replace("FORMAT", qualifier).replace("TAIL_TEXELS", Integer.toString(TAIL_TEXELS));
	}

	/** SPIR-V for the source, or null where shaderc refused it; freed by the caller. */
	static ByteBuffer compile(String source) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
				Shaderc.shaderc_env_version_vulkan_1_2);
		Shaderc.shaderc_compile_options_set_optimization_level(options, 2);
		long result = 0L;
		try {
			result = Shaderc.shaderc_compile_into_spv(compiler, source,
					Shaderc.shaderc_compute_shader, "vitrail_mip_chain.comp", "main", options);
			if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
				Vitrail.logger().warn("mip chain compute: {}",
						Shaderc.shaderc_result_get_error_message(result));
				return null;
			}

			ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
			ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
			MemoryUtil.memCopy(spirv, copy);

			return copy;
		} finally {
			if (result != 0L) {
				Shaderc.shaderc_result_release(result);
			}

			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}
}
