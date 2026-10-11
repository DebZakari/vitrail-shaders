package dev.vitrail.render;

/**
 * The readings of a pack's entity programs that stand, and the answer the two write roads into the
 * entity mesh take from them: whether a polygon's tangent has to be worked out at all.
 * {@code docs/internals/entities.md} says why it may be skipped.
 * <p>
 * <strong>A count and not a flag</strong>, because two readings can stand at once: the chain a load
 * replaces keeps its programs until it is released, the new chain reads its own on the load worker in
 * the meantime, and the two packs need not agree. One that reads the tangent is enough.
 * <p>
 * <strong>The answer is one volatile field moved under the lock</strong>, and not two counters read
 * where it is asked, which is once per run of a mob and once per polygon of a block entity. Two reads
 * could see a reading half counted, and the lock is only ever taken twice a load.
 * <p>
 * True until a reading stands, so whatever is written before the first one carries its tangent, and
 * true again once the last is released. A count that went below nought would be a release nothing
 * read, and it errs the same way rather than leaving the polygons without a tangent a program reads.
 */
final class TangentReadings {

	private int readings;

	private int declaring;

	private volatile boolean wanted = true;

	/** Whether a polygon written now has to have its tangent worked out. */
	boolean wanted() {
		return this.wanted;
	}

	/** One reading came in, and whether a program it kept for this mesh declares the tangent. */
	synchronized void read(boolean declares) {
		this.readings++;
		if (declares) {
			this.declaring++;
		}

		settle();
	}

	/** The reading counted with that same answer was released. */
	synchronized void released(boolean declares) {
		this.readings--;
		if (declares) {
			this.declaring--;
		}

		settle();
	}

	private void settle() {
		this.wanted = this.readings <= 0 || this.declaring > 0;
	}
}
