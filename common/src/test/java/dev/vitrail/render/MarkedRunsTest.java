package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link MarkedRuns} to what the lending rests on: every group the game builds is all marked
 * or all not, an ordered run keeps its order, and where nothing is marked the game groups exactly as
 * it did.
 * <p>
 * The grouping is a copy of the game's own ({@code FeatureRenderDispatcher.PhaseSubmitGrouper}: a
 * group is extended while the feature and the ordering match), with the two places the mixins act
 * on: an unordered run split before it is handed on, and the extension refused where
 * {@link MarkedRuns#apart} says so.
 */
class MarkedRunsTest {

	/** A submission: which feature it goes to, and a name to read a failure by. */
	private record Submit(int feature, String name) {
	}

	private record Group(int feature, boolean strictly, int from, int to) {
	}

	/** The game's grouper, with both hooks in or out. */
	private static final class Grouper {
		final List<Submit> all = new ArrayList<>();
		final List<Group> groups = new ArrayList<>();
		final Set<Submit> marked;
		final boolean hooked;

		/** Where this phase's groups start: each phase has a grouper of its own over the one list. */
		private int phaseStart;

		Grouper(Set<Submit> marked, boolean hooked) {
			this.marked = marked;
			this.hooked = hooked;
		}

		void nextPhase() {
			this.phaseStart = this.groups.size();
		}

		/** {@code SimpleFeatureRenderPhase.sortFeatureInto}'s call, through the split. */
		void unordered(int feature, List<Submit> run) {
			List<List<Submit>> runs = this.hooked ? MarkedRuns.split(run, this.marked) : null;
			if (runs == null) {
				acceptGroup(feature, run, false);
			} else {
				for (List<Submit> part : runs) {
					acceptGroup(feature, part, false);
				}
			}
		}

		/** {@code TranslucentFeatureRenderPhase.sortInto}'s call. */
		void ordered(Submit submit) {
			int index = this.all.size();
			this.all.add(submit);
			addOrExtend(submit.feature(), true, index, index);
		}

		private void acceptGroup(int feature, List<Submit> run, boolean strictly) {
			if (run.isEmpty()) {
				return;
			}

			int from = this.all.size();
			this.all.addAll(run);
			addOrExtend(feature, strictly, from, this.all.size() - 1);
		}

		private void addOrExtend(int feature, boolean strictly, int from, int to) {
			Group last = this.groups.size() > this.phaseStart ? this.groups.getLast() : null;
			boolean lastStrictly = last == null ? strictly : last.strictly();
			if (this.hooked && last != null && MarkedRuns.apart(this.all, from, this.marked)) {
				lastStrictly = !strictly;
			}

			if (last != null && last.feature() == feature && lastStrictly == strictly) {
				this.groups.set(this.groups.size() - 1, new Group(feature, strictly, last.from(), to));
			} else {
				this.groups.add(new Group(feature, strictly, from, to));
			}
		}

		void assertGroupsOfOneKind() {
			for (Group group : this.groups) {
				boolean first = this.marked.contains(this.all.get(group.from()));
				for (int i = group.from(); i <= group.to(); i++) {
					assertEquals(first, this.marked.contains(this.all.get(i)),
							"group " + group + " mixes " + this.all.get(group.from()) + " and "
									+ this.all.get(i));
				}
			}
		}
	}

	private static Set<Submit> marks() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}

	@Test
	void nothingMarkedGroupsAsTheGameDoes() {
		SplittableRandom random = new SplittableRandom(7);
		Set<Submit> none = marks();
		for (int round = 0; round < 200; round++) {
			Grouper hooked = new Grouper(none, true);
			Grouper plain = new Grouper(none, false);
			feed(random.split(), hooked, plain, null);
			assertEquals(plain.groups, hooked.groups);
			assertEquals(plain.all, hooked.all);
		}
	}

	@Test
	void everyGroupIsOfOneKind() {
		SplittableRandom random = new SplittableRandom(11);
		for (int round = 0; round < 500; round++) {
			Set<Submit> marked = marks();
			Grouper hooked = new Grouper(marked, true);
			feed(random.split(), hooked, null, marked);
			hooked.assertGroupsOfOneKind();
		}
	}

	@Test
	void anUnhookedGrouperMixesThem() {
		// The test above would pass on a grouper that never extends anything; this one says the
		// hooks are what does it.
		Set<Submit> marked = marks();
		Submit a = new Submit(1, "a");
		Submit b = new Submit(1, "b");
		marked.add(b);
		Grouper plain = new Grouper(marked, false);
		plain.unordered(1, List.of(a, b));
		assertEquals(1, plain.groups.size());

		Grouper hooked = new Grouper(marked, true);
		hooked.unordered(1, List.of(a, b));
		assertEquals(2, hooked.groups.size());
		hooked.assertGroupsOfOneKind();
	}

	@Test
	void anOrderedRunKeepsItsOrder() {
		SplittableRandom random = new SplittableRandom(13);
		Set<Submit> marked = marks();
		List<Submit> order = new ArrayList<>();
		Grouper hooked = new Grouper(marked, true);
		for (int i = 0; i < 64; i++) {
			Submit submit = new Submit(random.nextInt(3), "t" + i);
			if (random.nextBoolean()) {
				marked.add(submit);
			}

			order.add(submit);
			hooked.ordered(submit);
		}

		assertEquals(order, hooked.all);
		hooked.assertGroupsOfOneKind();
	}

	@Test
	void splitKeepsEachKindInItsOrder() {
		Set<Submit> marked = marks();
		List<Submit> run = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			Submit submit = new Submit(0, "s" + i);
			run.add(submit);
			if (i % 3 == 0) {
				marked.add(submit);
			}
		}

		List<List<Submit>> runs = MarkedRuns.split(run, marked);
		assertEquals(List.of(run.get(1), run.get(2), run.get(4), run.get(5), run.get(7), run.get(8)),
				runs.get(0));
		assertEquals(List.of(run.get(0), run.get(3), run.get(6), run.get(9)), runs.get(1));
	}

	@Test
	void runsOfOneKindGoOnAsTheyAre() {
		Set<Submit> marked = marks();
		Submit a = new Submit(0, "a");
		Submit b = new Submit(0, "b");
		assertNull(MarkedRuns.split(List.of(a, b), marked));

		marked.add(a);
		marked.add(b);
		assertNull(MarkedRuns.split(List.of(a, b), marked));
		assertNull(MarkedRuns.split(List.of(a), marked));
	}

	@Test
	void apartOnlyWhereTheMarkChanges() {
		Set<Submit> marked = marks();
		Submit a = new Submit(0, "a");
		Submit b = new Submit(0, "b");
		Submit c = new Submit(0, "c");
		List<Submit> all = List.of(a, b, c);
		assertFalse(MarkedRuns.apart(all, 1, marked));

		marked.add(b);
		assertFalse(MarkedRuns.apart(all, 0, marked));
		assertTrue(MarkedRuns.apart(all, 1, marked));
		assertTrue(MarkedRuns.apart(all, 2, marked));
		assertFalse(MarkedRuns.apart(all, 3, marked));

		marked.add(c);
		assertFalse(MarkedRuns.apart(all, 2, marked));
	}

	/**
	 * A frame's worth of phases: a few unordered ones, each a run per feature and per batch, and an
	 * ordered one, fed alike to both groupers (the second may be null) and marked at random where
	 * {@code marked} is given.
	 */
	private static void feed(SplittableRandom random, Grouper first, Grouper second, Set<Submit> marked) {
		int phases = 1 + random.nextInt(4);
		int named = 0;
		for (int phase = 0; phase < phases; phase++) {
			first.nextPhase();
			if (second != null) {
				second.nextPhase();
			}

			if (random.nextInt(4) == 0) {
				int count = random.nextInt(20);
				for (int i = 0; i < count; i++) {
					Submit submit = submit(random, marked, named++);
					first.ordered(submit);
					if (second != null) {
						second.ordered(submit);
					}
				}
			} else {
				int runs = random.nextInt(8);
				for (int r = 0; r < runs; r++) {
					int feature = random.nextInt(4);
					int size = random.nextInt(12);
					List<Submit> run = new ArrayList<>(size);
					for (int i = 0; i < size; i++) {
						run.add(new Submit(feature, "s" + named++));
						if (marked != null && random.nextInt(3) == 0) {
							marked.add(run.getLast());
						}
					}

					first.unordered(feature, run);
					if (second != null) {
						second.unordered(feature, run);
					}
				}
			}
		}
	}

	private static Submit submit(SplittableRandom random, Set<Submit> marked, int named) {
		Submit submit = new Submit(random.nextInt(4), "t" + named);
		if (marked != null && random.nextInt(3) == 0) {
			marked.add(submit);
		}

		return submit;
	}
}
