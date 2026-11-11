package dev.vitrail.render;

import dev.vitrail.Vitrail;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.GizmoFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.feature.ShapeOutlineFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Lends the picture's entity draws to the shadow map, in place of extracting, posing and submitting
 * the same entities a second time for the light.
 * <p>
 * <strong>What the map gets is what the picture drew.</strong> {@link ShadowGeometry} walks the
 * world again at the end of the frame and builds every caster anew; for an entity the picture
 * already built that is the same pose submitted twice, and the second extraction is the larger half
 * of what the walk costs. Here the picture's extraction says which of its entities the walk would
 * have taken, the submissions of those entities are kept in draws of their own, and the walk draws
 * those draws again into the map, through the same door and the same shadow table, and extracts only
 * what the picture did not.
 * <p>
 * <strong>The walk's own test decides, asked at the picture's extraction.</strong> An entity the
 * picture extracts is a caster if {@link ShadowGeometry#pictureCaster} says so: the walk's
 * {@code visible} against the pack's reach about the camera, so a pack whose movers stop short of
 * the world keeps the same casters, and whatever hooks that test hooks this too. Two things it asks
 * are the picture's and not the light's, the light's walk not having run yet: the light's planes are
 * not asked, and the terrain tree that test asks is the camera's. So a caster can be lent that the
 * walk would have dropped, outside the light's volume or outside its sweep, and either way it shadows
 * nothing the camera sees: a draw, never a pixel. None the walk would take is lost, every entity the
 * picture extracts having passed the camera's tree already.
 * <p>
 * <strong>Kept apart by marks on the submissions.</strong> While the picture submits a caster, every
 * submission it makes is marked, whichever feature it goes to. The game groups a phase's submissions
 * into runs and builds one draw per render type per run, so a run is cut where the mark changes
 * ({@link MarkedRuns}): a draw is then all caster or all not, and only the first kind is kept. What
 * no caster submitted is never lent, which keeps out the block entities, the hand, the outlines and
 * whatever a mod draws in the level that is not an entity.
 * <p>
 * <strong>Left to the walk unless it was lent.</strong> The walk leaves out a caster of the picture
 * only when a draw it submitted was lent; one whose draws all went elsewhere (another pass, a renderer
 * of a mod's own) is extracted again as before. So is an entity whose state the dispatcher did not
 * make: a mod that hands the picture a stand-in, as an occlusion cull does for an entity it hides,
 * would otherwise take the entity's shadow with it.
 * <p>
 * <strong>Lent for the frame and no longer.</strong> The draws point into the frame's pooled vertex
 * buffer, which goes back to its pool after the screen is drawn, and the shadow stage stands before
 * that. Everything is cleared at the next extraction.
 * <p>
 * <strong>Armed one frame ahead.</strong> Which families the pack asks of the map is known at the
 * shadow stage, after the picture is extracted, so a frame lends only when the previous frame's stage
 * asked for the entities. A frame that is not armed is today's walk throughout.
 * <p>
 * On by default; {@code off} in {@code vitrail/shadow-replay} beside the pack turns it off at the
 * next launch.
 */
public final class ShadowReuse {

	private static final String SETTING_FILE = "shadow-replay";

	/** Whether the replay is on for this session, read once from the launch file. */
	private static final boolean ENABLED = read();

	/** The entities the picture extracted this frame, while armed, that the walk would not take. */
	private static final Set<Entity> PICTURE = Collections.newSetFromMap(new IdentityHashMap<>());

	/** The states of those the walk would have taken, which are the ones whose draws are lent. */
	private static final Map<EntityRenderState, Entity> CASTERS = new IdentityHashMap<>();

	/** Every submission a caster made while the picture submitted it, with the caster. */
	private static final Map<SubmitNode, Entity> MARKED = new IdentityHashMap<>();

	/** The casters with at least one draw lent. */
	private static final Set<Entity> DRAWN = Collections.newSetFromMap(new IdentityHashMap<>());

	/** One of the picture's draws, kept for the light. */
	private record Lent(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info) {
	}

	private static final List<Lent> LENT = new ArrayList<>();

	/** Counts the picture's extractions, which is what arming is measured in. */
	private static long frame;

	/** The frame whose shadow stage last asked for the entities. */
	private static long askedFrame = Long.MIN_VALUE;

	/** Whether this frame's picture is being recorded for the map. */
	private static boolean armed;

	/** Whether the walk of this frame replays instead of extracting the picture's entities again. */
	private static boolean replaying;

	/** The pack's reach about the camera, built at the head of an armed frame. */
	private static Frustum reach;
	private static Vec3 at;

	/** How deep the entity submissions are nested, and the caster the outermost is, if any. */
	private static int depth;
	private static Entity marking;

	/** The state the dispatcher last made while the picture extracted one entity. */
	private static EntityRenderState dispatched;

	private static boolean saidBroken;

	private ShadowReuse() {
	}

	/** At the head of the picture's entity extraction. */
	public static void frameStart(Vec3 camera) {
		frame++;
		PICTURE.clear();
		CASTERS.clear();
		MARKED.clear();
		DRAWN.clear();
		LENT.clear();
		replaying = false;
		depth = 0;
		marking = null;
		dispatched = null;

		armed = ENABLED && askedFrame == frame - 1 && !held();
		reach = armed ? ShadowGeometry.pictureReach(camera) : null;
		at = camera;
	}

	/** Before the picture extracts an entity. */
	public static void extracting() {
		dispatched = null;
	}

	/** For each state the dispatcher makes; of nested ones the outermost comes last. */
	public static void dispatched(EntityRenderState state) {
		if (armed) {
			dispatched = state;
		}
	}

	/** For each entity the picture extracts, with the state it extracted. */
	public static void extracted(Entity entity, EntityRenderState state, float partial) {
		boolean made = state != null && state == dispatched;
		dispatched = null;
		if (!armed || !made) {
			// Not the dispatcher's state: the walk extracts the entity itself, as before.
			return;
		}

		try {
			if (ShadowGeometry.pictureCaster(Minecraft.getInstance(), entity, reach, at, partial)) {
				CASTERS.put(state, entity);
			} else {
				PICTURE.add(entity);
			}
		} catch (RuntimeException e) {
			// Left to the walk, which asks the same test and handles a failure of its own.
			if (!saidBroken) {
				saidBroken = true;
				Vitrail.logger().warn("Could not tell whether an entity casts a shadow; the light's walk "
						+ "extracts it again", e);
			}
		}
	}

	/** At the head of each entity submission; hands back what {@link #leave} restores. */
	public static Entity enter(EntityRenderState state) {
		Entity was = marking;
		if (depth++ == 0) {
			// The outermost submission decides: what an entity submits inside its own is part of it.
			marking = armed ? CASTERS.get(state) : null;
		}

		return was;
	}

	/** At the end of each entity submission, however it ended. */
	public static void leave(Entity was) {
		depth--;
		marking = was;
	}

	/** For every submission the game files into a phase. */
	public static void submitted(SubmitNode submit) {
		if (marking != null) {
			MARKED.put(submit, marking);
		}
	}

	/** Whether the game has to start a new group at {@code from} rather than extend the last one. */
	public static boolean apart(List<SubmitNode> submits, int from) {
		return MarkedRuns.apart(submits, from, MARKED.keySet());
	}

	/** A run the game may reorder, split by the mark, or null where it holds one kind. */
	public static <T> List<List<T>> runs(Collection<T> submits) {
		return MarkedRuns.split(submits, MARKED.keySet());
	}

	/**
	 * For each draw the game executes, one line before the door: keeps it when it is the picture's and
	 * a caster's.
	 *
	 * @param renderer the feature renderer executing it
	 * @param submits  the group the draw was built from, all marked or none
	 */
	public static void executing(Object renderer, PreparedRenderType prepared,
			StagedVertexBuffer.ExecuteInfo info, List<?> submits) {
		if (!armed || MARKED.isEmpty() || submits.isEmpty() || !MARKED.containsKey(submits.get(0))
				|| !EntityDraw.pictureWindowOpen() || BlockEntityGeometry.drawing()
				// The ground oval and the outlines a caster submits are not part of its body, and
				// the walk's own submission of the same caster drops them anyway.
				|| renderer instanceof ShadowFeatureRenderer || renderer instanceof ShapeOutlineFeatureRenderer
				|| renderer instanceof GizmoFeatureRenderer) {
			return;
		}

		LENT.add(new Lent(prepared, info));
		for (Object submit : submits) {
			DRAWN.add(MARKED.get(submit));
		}
	}

	/** At the shadow stage, before the walk extracts: whether the pack asked for the entities. */
	static void gathered(boolean entities) {
		if (entities) {
			askedFrame = frame;
		}

		// Nothing lent, nothing skipped: the walk then takes every caster as it always did.
		replaying = entities && armed && !LENT.isEmpty() && !held();
	}

	/**
	 * Whether the walk leaves this entity out: a caster of the picture's with a draw lent, or one of
	 * the picture's the walk would not take.
	 */
	static boolean skip(Entity entity) {
		return replaying && (DRAWN.contains(entity) || PICTURE.contains(entity));
	}

	static boolean replaying() {
		return replaying;
	}

	/** How many of the picture's draws are lent this frame. */
	static int lent() {
		return LENT.size();
	}

	/** Draws the lent draws into the map, inside the light's window. */
	static void replay() {
		// The shared indices first and outside any pass: growing them is an upload. The picture's
		// own draws asked for the same counts, so in practice nothing grows.
		for (Lent kept : LENT) {
			if (kept.info().customIndexBuffer() == null) {
				RenderSystem.getSequentialBuffer(kept.info().topology()).getBuffer(kept.info().indexCount());
			}
		}

		GameRender.replayFeatures(() -> {
			try {
				for (Lent kept : LENT) {
					StagedVertexBuffer.ExecuteInfo info = kept.info();
					if (info.customIndexBuffer() == null) {
						// The index type the shared indices have now, which a growth since the
						// picture would change.
						info = new StagedVertexBuffer.ExecuteInfo(info.vertexBuffer(), null,
								RenderSystem.getSequentialBuffer(info.topology()).type(),
								info.baseVertex(), info.firstIndex(), info.indexCount(), info.topology());
					}

					BlockEntityGeometry.drawing(false);
					EntityDraw.draw(kept.prepared(), info);
				}
			} finally {
				EntityDraw.endGroup();
			}
		}, () -> "Vitrail shadow replay");
	}

	/** At the end of the shadow stage, whatever happened in it. */
	static void walked() {
		LENT.clear();
		replaying = false;
	}

	/**
	 * Another mod's hold on the replay, for a check that compares pictures with and without its own
	 * work: a public static {@code boolean suspended()} on {@code dev.vultrum.cull.ShadowPass}, looked
	 * up once. Absent, nothing holds it.
	 */
	private static MethodHandle hold;
	private static boolean holdLooked;

	private static boolean held() {
		if (!holdLooked) {
			holdLooked = true;
			try {
				hold = MethodHandles.publicLookup().findStatic(Class.forName("dev.vultrum.cull.ShadowPass"),
						"suspended", MethodType.methodType(boolean.class));
			} catch (ReflectiveOperationException | LinkageError e) {
				hold = null;
			}
		}

		try {
			return hold != null && (boolean) hold.invokeExact();
		} catch (Throwable e) {
			return false;
		}
	}

	private static boolean read() {
		boolean on = true;
		try {
			Path file = Vitrail.platform().gameDirectory().resolve("vitrail").resolve(SETTING_FILE);
			if (Files.isRegularFile(file)) {
				String asked = Files.readString(file).trim().toLowerCase(Locale.ROOT);
				on = !(asked.equals("off") || asked.equals("false") || asked.equals("0"));
			}
		} catch (Exception ignored) {
			// Unreadable reads as the default.
		}

		Vitrail.logger().info("The shadow map {} the picture's entity draws", on ? "replays" : "does not replay");
		return on;
	}
}
