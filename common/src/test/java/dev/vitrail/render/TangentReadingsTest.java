package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds the entity mesh's tangent to the programs that stand: skipped only while every standing
 * reading leaves it unread, and worked out before the first reading and after the last.
 * <p>
 * The skip is the one direction that can go wrong on screen. A polygon written with the axis where a
 * program reads the tangent lights a normal map along the wrong direction and nothing says so, while
 * a tangent worked out for nobody only costs time.
 */
class TangentReadingsTest {

	@Test
	void worksItOutBeforeAnyReading() {
		assertTrue(new TangentReadings().wanted(), "nothing read yet");
	}

	@Test
	void skipsItWhileTheOneReadingLeavesItUnread() {
		TangentReadings readings = new TangentReadings();
		readings.read(false);
		assertFalse(readings.wanted(), "one reading, and it does not declare at_tangent");

		readings.released(false);
		assertTrue(readings.wanted(), "nothing stands once it is released");
	}

	@Test
	void worksItOutWhileTheOneReadingDeclaresIt() {
		TangentReadings readings = new TangentReadings();
		readings.read(true);
		assertTrue(readings.wanted());
	}

	@Test
	void aLoadThatDeclaresItTakesOverFromOneThatDoesNot() {
		TangentReadings readings = new TangentReadings();
		readings.read(false);
		readings.read(true);
		assertTrue(readings.wanted(), "the new chain read beside the old one");

		readings.released(false);
		assertTrue(readings.wanted(), "the old chain released");
	}

	@Test
	void aLoadThatDoesNotDeclareItTakesOverFromOneThatDoes() {
		TangentReadings readings = new TangentReadings();
		readings.read(true);
		readings.read(false);
		assertTrue(readings.wanted(), "the old chain's programs still stand");

		readings.released(true);
		assertFalse(readings.wanted(), "only the new chain stands");
	}

	@Test
	void aReleaseNothingReadLeavesItWorkedOut() {
		TangentReadings readings = new TangentReadings();
		readings.released(false);
		assertTrue(readings.wanted(), "a count below nought");

		readings.read(false);
		assertTrue(readings.wanted(), "back at nought, which is nothing standing");
	}
}
