package com.example.client

import com.example.*
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.phys.Vec3
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback
import net.minecraft.client.renderer.entity.player.PlayerRenderer
import org.lwjgl.glfw.GLFW

class HelperRenderer(ctx: EntityRendererProvider.Context) :
	HumanoidMobRenderer<Helper, HumanoidModel<Helper>>(ctx, HumanoidModel(ctx.bakeLayer(ModelLayers.PLAYER)), 0.4f) {
	override fun getTextureLocation(e: Helper): ResourceLocation = TemplateMod.id("textures/entity/helper.png")
}

object TemplateModClient : ClientModInitializer {
	private var prevJump = false

	override fun onInitializeClient() {
		val ability = KeyBindingHelper.registerKeyBinding(KeyMapping("key.remake.ability", GLFW.GLFW_KEY_R, "key.categories.remake"))
		val dash = KeyBindingHelper.registerKeyBinding(KeyMapping("key.remake.dash", GLFW.GLFW_KEY_V, "key.categories.remake"))
		EntityRendererRegistry.register(ModEntities.BRICK) { ctx -> ThrownItemRenderer<ThrownBrick>(ctx) }
		EntityRendererRegistry.register(ModEntities.HEAD) { ctx -> ThrownItemRenderer<ThrownHead>(ctx) }
		EntityModelLayerRegistry.registerModelLayer(WingsLayer.LAYER) { WingsLayer.layer() }
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register { _, renderer, helper, ctx ->
			if (renderer is PlayerRenderer) helper.register(WingsLayer(renderer, ctx.modelSet))
		}
		EntityRendererRegistry.register(ModEntities.HELPER) { ctx -> HelperRenderer(ctx) }

		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			while (ability.consumeClick()) ClientPlayNetworking.send(ActionPayload(0))
			while (dash.consumeClick()) ClientPlayNetworking.send(ActionPayload(1))
			val p = mc.player ?: return@register
			val jump = mc.options.keyJump.isDown
			val m = p.deltaMovement; val look = p.lookAngle
			val h = Vec3(look.x, 0.0, look.z).normalize()
			val claws = p.mainHandItem.`is`(ModItems.CLAWS) || p.offhandItem.`is`(ModItems.CLAWS)
			val wings = p.getItemBySlot(EquipmentSlot.CHEST).`is`(ModItems.WINGS)
			// Когти: лазание по стенам (Shift — висеть)
			if (claws && p.horizontalCollision) {
				if (p.isShiftKeyDown) p.deltaMovement = Vec3(m.x, 0.0, m.z)
				else if (mc.options.keyUp.isDown || jump) p.deltaMovement = Vec3(m.x, 0.22, m.z)
			}
			if (wings && !p.onGround() && !p.isInWater) {
				if (jump && !prevJump && p.horizontalCollision) {              // бесплатный отпрыг от стены
					p.deltaMovement = Vec3(-h.x * 0.35, 0.6, -h.z * 0.35); p.resetFallDistance()
				} else if (jump && m.y < -0.1) {                               // скольжение (без расхода энергии)
					p.deltaMovement = Vec3(m.x + h.x * 0.03, -0.08, m.z + h.z * 0.03); p.resetFallDistance()
				}
			}
			prevJump = jump
		}
	}
}
