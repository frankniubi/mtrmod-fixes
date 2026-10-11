package org.mtr.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.mtr.mod.client.VehicleRidingMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraSeatedMixin {

	@Shadow
	public abstract Vec3d getPos();

	@Shadow
	protected abstract void setPos(Vec3d pos);

	@Inject(method = "update", at = @At(value = "TAIL"))
	private void lowerSeatedCamera(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo callbackInfo) {
		if (!thirdPerson && VehicleRidingMovement.isSeated()) {
			setPos(getPos().subtract(0, VehicleRidingMovement.SEATED_EYE_DROP, 0));
		}
	}
}
