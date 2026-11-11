package dev.vitrail.render;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Keeps marked submissions and unmarked ones out of each other's draw groups, the arithmetic of it
 * without the game's types so that it can be tested on its own.
 * <p>
 * The game builds one draw per render type per group, so a group that holds both kinds yields draws
 * that hold both kinds, and a draw cannot be lent half. A group is cut where the mark changes and
 * nowhere else: a run the game orders strictly stays in its order, and a run it may reorder is split
 * in two, which is a reordering the game allows itself in that run anyway.
 */
final class MarkedRuns {

	private MarkedRuns() {
	}

	/**
	 * Whether the submission at {@code from} starts a group apart from the one before it, which the
	 * game would otherwise extend: true where exactly one of the two is marked.
	 * <p>
	 * The one before it is the last of the group it would extend. The groups of one phase are built
	 * in order onto the end of the frame's one list, so whatever was added last is at
	 * {@code from - 1}.
	 */
	static boolean apart(List<?> submits, int from, Set<?> marked) {
		if (marked.isEmpty() || from <= 0 || from >= submits.size()) {
			return false;
		}

		return marked.contains(submits.get(from)) != marked.contains(submits.get(from - 1));
	}

	/**
	 * The unmarked and the marked submissions of a run the game may reorder, as two runs in their own
	 * order, or null where the run holds only one kind and goes to the game as it is.
	 */
	static <T> List<List<T>> split(Collection<T> submits, Set<?> marked) {
		if (marked.isEmpty() || submits.size() < 2) {
			return null;
		}

		// Counted before anything is copied: nearly every run is of one kind, and those go on as
		// they are.
		int count = 0;
		for (T submit : submits) {
			if (marked.contains(submit)) {
				count++;
			}
		}

		if (count == 0 || count == submits.size()) {
			return null;
		}

		List<T> plain = new ArrayList<>(submits.size() - count);
		List<T> kept = new ArrayList<>(count);
		for (T submit : submits) {
			(marked.contains(submit) ? kept : plain).add(submit);
		}

		return List.of(plain, kept);
	}
}
