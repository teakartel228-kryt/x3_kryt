package com.example

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.projectile.Projectile

data class ActionPayload(val action: Int) : CustomPacketPayload {
	override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
	companion object {
		val TYPE = CustomPacketPayload.Type<ActionPayload>(TemplateMod.id("action"))
		val CODEC: StreamCodec<RegistryFriendlyByteBuf, ActionPayload> =
			StreamCodec.of({ buf, p -> buf.writeVarInt(p.action) }, { buf -> ActionPayload(buf.readVarInt()) })
	}
}

object TemplateMod : ModInitializer {
	const val MOD_ID: String = "remake"

	fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

	override fun onInitialize() {
		ModBlocks; ModEntities.init(); ModItems.init()
		PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.CODEC)
		ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE) { payload, ctx ->
			when (payload.action) { 0 -> ability(ctx.player()); 1 -> dash(ctx.player()) }
		}
		// Энергия рывков восстанавливается сама (3 рывка максимум)
		ServerTickEvents.END_SERVER_TICK.register { s ->
			for (p in s.playerList.players) Rt.energy[p.uuid] = minOf(3f, (Rt.energy[p.uuid] ?: 3f) + 0.02f)
		}
		// Неуязвимость только пока длится рывок
		ServerLivingEntityEvents.ALLOW_DAMAGE.register { e, src, amt -> !(e is ServerPlayer && (Rt.dashUntil[e.uuid] ?: 0L) > e.level().gameTime) && !guardAbsorb(e, src, amt) }
		// Бросок кирпичей (обычных и незерских)
		UseItemCallback.EVENT.register { p, level, hand ->
			val st = p.getItemInHand(hand); val item = st.item
			if (item == Items.BRICK || item == Items.NETHER_BRICK) {
				if (!level.isClientSide) {
					val b = ThrownBrick(level, p, st.copyWithCount(1))
					b.shootFromRotation(p, p.xRot, p.yRot, 0f, 1.6f, 1f); level.addFreshEntity(b)
					level.playSound(null, p.x, p.y, p.z, SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6f, 0.8f)
				}
				if (!p.abilities.instabuild) st.shrink(1)
				p.cooldowns.addCooldown(item, 6)
				InteractionResultHolder.sidedSuccess(st, level.isClientSide)
			} else InteractionResultHolder.pass(st)
		}
	}

	// Защитная цепь крюка (зачарование chain_guard): ловит снаряды и ближние удары, контратакует
	private fun guardAbsorb(e: LivingEntity, src: DamageSource, amt: Float): Boolean {
		if (e !is ServerPlayer) return false
		val lvl = e.serverLevel(); val h = Rt.hookIn(lvl, e) ?: return false
		val now = lvl.gameTime
		if ((Rt.guardRegen[e.uuid] ?: 0L) > now) return false          // цепь восстанавливается после экстренного режима
		val atk = src.directEntity ?: return false
		if (atk !is Projectile && atk !is LivingEntity) return false
		val hp = (Rt.guard[e.uuid] ?: 12f) - amt
		if (hp <= 0f) { Rt.guard[e.uuid] = 12f; Rt.guardRegen[e.uuid] = now + Rt.emergencyCd(lvl, h); return false }
		Rt.guard[e.uuid] = hp
		if (atk is Projectile) { atk.deltaMovement = atk.deltaMovement.scale(-1.0); atk.owner = e; atk.hurtMarked = true }
		else if (atk is LivingEntity) { atk.hurt(lvl.damageSources().playerAttack(e), 3f); atk.knockback(0.6, e.x - atk.x, e.z - atk.z) }
		lvl.sendParticles(ParticleTypes.CRIT, e.x, e.y + 1.0, e.z, 10, 0.4, 0.4, 0.4, 0.1)
		lvl.playSound(null, e.blockPosition(), SoundEvents.CHAIN_HIT, SoundSource.PLAYERS, 1f, 1f)
		return true
	}

	private fun ability(p: ServerPlayer) {
		val lvl = p.serverLevel()
		if (p.mainHandItem.`is`(ModItems.SACK) || p.offhandItem.`is`(ModItems.SACK)) { SackItem.lightning(lvl, p); return }
		if (p.getItemBySlot(EquipmentSlot.HEAD).`is`(ModItems.NOTE_HELMET)) wave(lvl, p)
	}

	// Нотный шлем: звуковая волна, блоки её останавливают
	private fun wave(lvl: ServerLevel, p: ServerPlayer) {
		if (!Rt.ready(p, "wave", 100)) return
		for (e in lvl.getEntitiesOfClass(LivingEntity::class.java, p.boundingBox.inflate(6.0)) { it !== p && it !is Helper }) {
			if (Rt.blocked(lvl, p.eyePosition, e.eyePosition)) continue
			e.hurt(lvl.damageSources().sonicBoom(p), 5f)
			val d = e.position().subtract(p.position()).multiply(1.0, 0.0, 1.0).normalize()
			e.deltaMovement = Vec3(d.x * 1.2, 0.4, d.z * 1.2); e.hurtMarked = true
		}
		for (i in 0 until 24) for (r in 1..5) {
			val a = i * Math.PI / 12
			lvl.sendParticles(ParticleTypes.NOTE, p.x + Math.cos(a) * r, p.y + 1.0, p.z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0)
		}
		lvl.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.5f, 0.8f)
	}

	// Рывок: с электро-крыльями (энергия, неуязвимость) ИЛИ при ползании/приседании без крыльев
	private fun dash(p: ServerPlayer) {
		val wings = p.getItemBySlot(EquipmentSlot.CHEST).`is`(ModItems.WINGS)
		val crawl = (p.pose == Pose.SWIMMING && !p.isInWater) || p.isCrouching
		val look = p.lookAngle
		val v: Vec3
		val en = Rt.energy[p.uuid] ?: 3f
		if (wings && en >= 1f) {
			Rt.energy[p.uuid] = en - 1f; Rt.dashUntil[p.uuid] = p.level().gameTime + 8
			v = look.scale(1.7)
			p.displayClientMessage(Component.translatable("msg.remake.dashes", (en - 1f).toInt()), true)
		} else if (crawl && Rt.ready(p, "crawldash", 25)) {
			v = Vec3(look.x, 0.0, look.z).normalize().scale(1.5)
		} else return
		p.deltaMovement = v; p.hurtMarked = true; p.resetFallDistance()
		p.serverLevel().sendParticles(ParticleTypes.CLOUD, p.x, p.y + 0.3, p.z, 8, 0.2, 0.1, 0.2, 0.02)
	}
}
