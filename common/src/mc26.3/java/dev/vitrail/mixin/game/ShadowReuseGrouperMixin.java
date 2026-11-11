package dev.vitrail.mixin.game;

import dev.vitrail.render.ShadowReuse;

import java.util.List;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Starts a new group where the mark changes, instead of extending the last one.
 * <p>
 * The grouper extends its last group when the feature and the ordering match, and the ordering is
 * the second half of that test: read where the mark changes, it comes back as the opposite of what
 * is asked, the test fails and a group is started. Nothing else changes, the ordering of the new
 * group included, which is taken from the argument and not from the field.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PhaseSubmitGrouper")
public abstract class ShadowReuseGrouperMixin {

	@Shadow
	@Final
	private List<SubmitNode> allSubmits;

	@ModifyExpressionValue(method = "addOrExtendGroup", require = 1, allow = 1,
			at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
					target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedGroup;"
							+ "strictlyOrdered:Z"))
	private boolean vitrail$apart(boolean last, @Local(argsOnly = true) boolean strictlyOrdered,
			@Local(argsOnly = true, ordinal = 0) int fromInclusive) {
		return ShadowReuse.apart(this.allSubmits, fromInclusive) ? !strictlyOrdered : last;
	}
}
