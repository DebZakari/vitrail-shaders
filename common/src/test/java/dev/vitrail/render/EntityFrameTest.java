package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link EntityFrame#tangent} to the two things its split promised: every piece small enough
 * for C2 to inline, and every word the same as the one method it was split from.
 * <p>
 * The first is not something a mob on screen would ever show. Over the limit the arithmetic still
 * comes out right and the serializer only gets slower, so nothing but a count of bytecode notices.
 */
class EntityFrameTest {

	/** C2's default {@code FreqInlineSize}: the most bytecode of a hot method it inlines. */
	private static final int INLINED = 325;

	/** {@code EntityFrame.TINY}, which the reference below needs and the class keeps private. */
	private static final float TINY = 1.0E-12F;

	@Test
	void everyPieceInlines() throws IOException {
		ClassModel frame;
		try (InputStream in = EntityFrame.class.getResourceAsStream("EntityFrame.class")) {
			assertNotNull(in, "EntityFrame's class file");
			frame = ClassFile.of().parse(in.readAllBytes());
		}

		int seen = 0;
		for (MethodModel method : frame.methods()) {
			String name = method.methodName().stringValue();
			if (!List.of("tangent", "gradients", "frame", "pack").contains(name)) {
				continue;
			}
			int length = method.findAttribute(Attributes.code())
					.map(CodeAttribute::codeLength).orElseThrow();
			assertTrue(length <= INLINED, name + " is " + length + " bytes of bytecode, over the "
					+ INLINED + " C2 inlines of a hot method");
			seen++;
		}
		assertEquals(4, seen, "tangent, gradients, frame and pack");
	}

	@Test
	void theSplitKeepsEveryWord() {
		SplittableRandom random = new SplittableRandom(42L);
		float[] corners = new float[15];
		for (int i = 0; i < 2_000_000; i++) {
			int kind = i % 7;
			for (int k = 0; k < corners.length; k++) {
				corners[k] = switch (kind) {
					case 0 -> (float) random.nextDouble(-2.0, 2.0);
					// An entity model is built in sixteenths of a block.
					case 1 -> (float) Math.floor(random.nextDouble(-64.0, 64.0)) / 16.0F;
					// Under TINY on both tests.
					case 2 -> (float) random.nextDouble(-1.0E-4, 1.0E-4);
					case 3 -> random.nextInt(3) == 0 ? 0.0F : (float) random.nextDouble(-1.0, 1.0);
					case 4 -> (float) random.nextDouble(-3.0E4, 3.0E4);
					// Any bits at all, NaN and the infinities among them.
					case 5 -> Float.intBitsToFloat(random.nextInt());
					// Three corners on one texture coordinate: no gradient to invert.
					default -> k % 5 >= 3 ? corners[k % 5] : (float) random.nextDouble(-2.0, 2.0);
				};
			}
			float x = (float) random.nextDouble(-1.0, 1.0);
			float y = (float) random.nextDouble(-1.0, 1.0);
			float z = (float) random.nextDouble(-1.0, 1.0);
			float length = (float) Math.sqrt(x * x + y * y + z * z);
			if (length > 0.0F) {
				x /= length;
				y /= length;
				z /= length;
			}

			for (boolean flatten : new boolean[] {false, true}) {
				int split = EntityFrame.tangent(x, y, z, flatten, corners[0], corners[1], corners[2],
						corners[3], corners[4], corners[5], corners[6], corners[7], corners[8],
						corners[9], corners[10], corners[11], corners[12], corners[13], corners[14]);
				int whole = oneMethod(x, y, z, flatten, corners[0], corners[1], corners[2],
						corners[3], corners[4], corners[5], corners[6], corners[7], corners[8],
						corners[9], corners[10], corners[11], corners[12], corners[13], corners[14]);
				if (split != whole) {
					assertEquals(whole, split, "the tangent of " + Arrays.toString(corners)
							+ " on (" + x + ", " + y + ", " + z + "), flattened " + flatten);
				}
			}
		}
	}

	/** {@link EntityFrame#tangent} as one method, as it stood before the split. */
	private static int oneMethod(float normalX, float normalY, float normalZ, boolean flatten,
			float x0, float y0, float z0, float u0, float v0,
			float x1, float y1, float z1, float u1, float v1,
			float x2, float y2, float z2, float u2, float v2) {
		float px0 = x0;
		float py0 = y0;
		float pz0 = z0;
		float px1 = x1;
		float py1 = y1;
		float pz1 = z1;
		float px2 = x2;
		float py2 = y2;
		float pz2 = z2;
		if (flatten) {
			float onto0 = x0 * normalX + y0 * normalY + z0 * normalZ;
			float onto1 = x1 * normalX + y1 * normalY + z1 * normalZ;
			float onto2 = x2 * normalX + y2 * normalY + z2 * normalZ;
			px0 -= onto0 * normalX;
			py0 -= onto0 * normalY;
			pz0 -= onto0 * normalZ;
			px1 -= onto1 * normalX;
			py1 -= onto1 * normalY;
			pz1 -= onto1 * normalZ;
			px2 -= onto2 * normalX;
			py2 -= onto2 * normalY;
			pz2 -= onto2 * normalZ;
		}

		float edge1x = px1 - px0;
		float edge1y = py1 - py0;
		float edge1z = pz1 - pz0;
		float edge2x = px2 - px0;
		float edge2y = py2 - py0;
		float edge2z = pz2 - pz0;

		float du1 = u1 - u0;
		float dv1 = v1 - v0;
		float du2 = u2 - u0;
		float dv2 = v2 - v0;

		float span = du1 * dv2 - du2 * dv1;
		float scale = span == 0.0F ? 1.0F : 1.0F / span;

		float tx = scale * (dv2 * edge1x - dv1 * edge2x);
		float ty = scale * (dv2 * edge1y - dv1 * edge2y);
		float tz = scale * (dv2 * edge1z - dv1 * edge2z);
		float square = tx * tx + ty * ty + tz * tz;
		if (square <= TINY) {
			return EntityFrame.FLAT;
		}

		float unit = (float) (1.0 / Math.sqrt(square));
		tx *= unit;
		ty *= unit;
		tz *= unit;

		float bx = scale * (-du2 * edge1x + du1 * edge2x);
		float by = scale * (-du2 * edge1y + du1 * edge2y);
		float bz = scale * (-du2 * edge1z + du1 * edge2z);
		float side = bx * (ty * normalZ - tz * normalY) + by * (tz * normalX - tx * normalZ)
				+ bz * (tx * normalY - ty * normalX);

		return EntityFrame.pack(tx, ty, tz, side < 0.0F ? -1.0F : 1.0F);
	}
}
