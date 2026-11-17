package dev.vitrail.pack.program;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.ProgramTranslator;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the shadow alpha policy through pack directives and the translated fragment stage. */
class TerrainPassTest {

	@TempDir
	Path pack;

	@Test
	void rejectsTransparentShadowTexelsAfterThePackWritesItsAlpha() throws IOException {
		String fragment = translateShadow("");

		assertTrue(fragment.contains(".a > 0.1"), fragment);
		assertTrue(fragment.contains("discard;"), fragment);
	}

	@Test
	void letsTheResolvedShadowProgramDisableTheAlphaTest() throws IOException {
		assertFalse(translateShadow("alphaTest.shadow=off").contains("discard;"));
	}

	@Test
	void letsTheResolvedShadowProgramChooseItsComparisonAndThreshold() throws IOException {
		String fragment = translateShadow("alphaTest.shadow=LESS 0.25");

		assertTrue(fragment.contains(".a < 0.25"), fragment);
		assertTrue(fragment.contains("discard;"), fragment);
	}

	private String translateShadow(String directive) throws IOException {
		Path shaders = Files.createDirectories(this.pack.resolve("shaders"));
		Files.writeString(shaders.resolve("shaders.properties"), directive);
		Files.writeString(shaders.resolve("shadow.vsh"), """
				#version 120
				varying vec2 texCoord;
				void main() {
					texCoord = gl_MultiTexCoord0.xy;
					gl_Position = gl_Vertex;
				}
				""");
		Files.writeString(shaders.resolve("shadow.fsh"), """
				#version 120
				/* DRAWBUFFERS:0 */
				uniform sampler2D tex;
				varying vec2 texCoord;
				void main() {
					gl_FragData[0] = texture2D(tex, texCoord);
				}
				""");
		try (ShaderPackSource source = ShaderPackSource.open(this.pack)) {
			var overrides = ShaderProperties.parse(source).alphaTests(Map.of());
			AlphaTest alpha = TerrainPass.SHADOW_TRANSLUCENT.alphaTest(overrides, "shadow");
			var expander = new IncludeExpander(source, SettingSet.defaults());
			var units = Map.of(
					ProgramStage.VERTEX, expander.expand(shaders.resolve("shadow.vsh")),
					ProgramStage.FRAGMENT, expander.expand(shaders.resolve("shadow.fsh")));
			return ProgramTranslator.translate(units, VertexInputs.TERRAIN, alpha,
					TerrainPass.SHADOW_TRANSLUCENT.program()).stages().get(ProgramStage.FRAGMENT).text();
		}
	}
}
