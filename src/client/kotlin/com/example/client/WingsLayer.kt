package com.example.client

import com.example.*
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.EntityModelSet
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.EquipmentSlot

/** Электро-крылья на спине игрока: по 3 жёлтых лезвия с каждой стороны. */
class WingsLayer(parent: RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>, set: EntityModelSet) :
	RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>(parent) {
	private val wings: ModelPart = set.bakeLayer(LAYER)

	override fun render(pose: PoseStack, buf: MultiBufferSource, light: Int, p: AbstractClientPlayer,
		a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
		if (!p.getItemBySlot(EquipmentSlot.CHEST).`is`(ModItems.WINGS)) return
		pose.pushPose()
		parentModel.body.translateAndRotate(pose)   // следует за наклоном тела (приседание)
		pose.translate(0f, 0f, 0.125f)
		wings.render(pose, buf.getBuffer(RenderType.entityCutoutNoCull(TEX)), light, OverlayTexture.NO_OVERLAY)
		pose.popPose()
	}

	companion object {
		val LAYER = ModelLayerLocation(TemplateMod.id("wings"), "main")
		val TEX = TemplateMod.id("textures/entity/wings.png")
		fun layer(): LayerDefinition {
			val m = MeshDefinition(); val root = m.root
			for (s in intArrayOf(-1, 1)) for (i in 0..2) {
				val len = 12f - i * 2f
				val box = CubeListBuilder.create().texOffs(0, i * 7)
				if (s > 0) box.addBox(0f, -0.5f, 0f, len, 1f, 1.5f) else box.addBox(-len, -0.5f, 0f, len, 1f, 1.5f)
				root.addOrReplaceChild("w${s}_$i", box, PartPose.offsetAndRotation(s * 1.5f, 2f + i * 2.5f, 0f, 0f, 0f, -s * (0.3f + 0.3f * i)))
			}
			return LayerDefinition.create(m, 32, 32)
		}
	}
}
