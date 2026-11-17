package com.example

import com.mojang.serialization.MapCodec
import java.util.*
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.*
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ThrowableItemProjectile
import net.minecraft.world.item.*
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.core.registries.Registries
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceKey
import net.minecraft.util.RandomSource
import net.minecraft.world.Container
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.properties.ChestType

/** Серверное состояние: энергия рывков, перезарядки. */
object Rt {
	val energy = HashMap<UUID, Float>()
	val dashUntil = HashMap<UUID, Long>()
	val cd = HashMap<String, Long>()
	val helperCd = HashMap<UUID, Long>()
	val guard = HashMap<UUID, Float>()
	val guardRegen = HashMap<UUID, Long>()
	val CRACK: Map<Block, Block> = mapOf(Blocks.STONE_BRICKS to Blocks.CRACKED_STONE_BRICKS, Blocks.DEEPSLATE_BRICKS to Blocks.CRACKED_DEEPSLATE_BRICKS,
		Blocks.DEEPSLATE_TILES to Blocks.CRACKED_DEEPSLATE_TILES, Blocks.NETHER_BRICKS to Blocks.CRACKED_NETHER_BRICKS,
		Blocks.POLISHED_BLACKSTONE_BRICKS to Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)

	/** Уровень зачарования remake:<name> (datapack) на предмете. */
	fun ench(level: Level, s: ItemStack, n: String): Int {
		val reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
		val h = reg.getHolder(ResourceKey.create(Registries.ENCHANTMENT, TemplateMod.id(n))).orElse(null) ?: return 0
		return EnchantmentHelper.getItemEnchantmentLevel(h, s)
	}
	fun emergencyCd(level: Level, s: ItemStack) = maxOf(60, 300 - 60 * ench(level, s, "quick_reload"))
	fun charges(s: ItemStack): Int = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt("rs")
	fun setCharges(s: ItemStack, n: Int) { CustomData.update(DataComponents.CUSTOM_DATA, s) { it.putInt("rs", n) } }
	fun hookIn(level: Level, p: Player): ItemStack? =
		listOf(p.mainHandItem, p.offhandItem).firstOrNull { it.item is HookItem && ench(level, it, "chain_guard") > 0 }

	fun ready(p: Player, key: String, ticks: Int): Boolean {
		val k = "${p.uuid}$key"; val t = p.level().gameTime
		if ((cd[k] ?: 0L) > t) return false
		cd[k] = t + ticks; return true
	}
	/** Луч по блокам с коллизией: (дистанция, блок или null). */
	fun march(level: Level, from: Vec3, dir: Vec3, max: Double): Pair<Double, BlockPos?> {
		var i = 0.3
		while (i <= max) {
			val bp = BlockPos.containing(from.add(dir.scale(i)))
			if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty) return Pair(i, bp)
			i += 0.25
		}
		return Pair(max, null)
	}
	fun blocked(level: Level, a: Vec3, b: Vec3) = march(level, a, b.subtract(a).normalize(), a.distanceTo(b)).second != null
	fun firstEntity(level: Level, p: Entity, eye: Vec3, look: Vec3, dist: Double): Pair<Entity, Double>? {
		val end = eye.add(look.scale(dist)); var best: Pair<Entity, Double>? = null
		for (e in level.getEntities(p, AABB(eye, end).inflate(1.0))) {
			if (!(e.isPickable || e is ItemEntity) || e is Helper) continue
			val h = e.boundingBox.inflate(0.3).clip(eye, end).orElse(null) ?: continue
			val d = eye.distanceTo(h)
			if (best == null || d < best.second) best = Pair(e, d)
		}
		return best
	}
}

// ───────────── ПРЕДМЕТЫ ─────────────

class ClawsItem : Item(Item.Properties().stacksTo(1).durability(500).attributes(
	ItemAttributeModifiers.builder()
		.add(Attributes.ATTACK_DAMAGE, AttributeModifier(TemplateMod.id("claws_dmg"), 2.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
		.add(Attributes.ATTACK_SPEED, AttributeModifier(TemplateMod.id("claws_spd"), -3.2, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
		.build())) {
	// 3 удара за одну атаку (основной + 2 дополнительных)
	override fun hurtEnemy(stack: ItemStack, target: LivingEntity, attacker: LivingEntity): Boolean {
		for (i in 0..1) { target.invulnerableTime = 0; target.hurt(attacker.damageSources().mobAttack(attacker), 2f) }
		stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND)
		return true
	}
}

class HandPistonItem : Item(Item.Properties().stacksTo(1).durability(300)) {
	override fun getUseAnimation(stack: ItemStack) = UseAnim.BOW
	override fun getUseDuration(stack: ItemStack) = 72000
	override fun use(level: Level, p: Player, h: InteractionHand): InteractionResultHolder<ItemStack> {
		p.startUsingItem(h); return InteractionResultHolder.consume(p.getItemInHand(h))
	}
	override fun releaseUsing(stack: ItemStack, level: Level, e: LivingEntity, timeLeft: Int) {
		if (level.isClientSide || e !is Player) return
		val charged = getUseDuration(stack) - timeLeft >= 20
		val k = if (charged) 2.0 else 1.0
		val look = e.lookAngle; val eye = e.eyePosition; val range = if (charged) 5.0 else 4.0
		var hit = false
		for (t in level.getEntities(e, e.boundingBox.expandTowards(look.scale(range)).inflate(1.0)) { it is LivingEntity }) {
			val to = t.boundingBox.center.subtract(eye)
			if (to.length() > range + 1 || to.normalize().dot(look) < 0.7) continue
			t.hurt(level.damageSources().playerAttack(e), (3.0 * k).toFloat())
			t.deltaMovement = look.scale(1.1 * k).add(0.0, 0.3, 0.0); t.hurtMarked = true; hit = true
		}
		val bh = e.pick(range, 1f, false)
		if (bh is BlockHitResult && bh.type == HitResult.Type.BLOCK) {
			val dir = bh.direction.opposite; val pos = bh.blockPos; val st = level.getBlockState(pos)
			val steps = if (charged) 2 else 1
			if (st.pushReaction == PushReaction.NORMAL && st.getDestroySpeed(level, pos) >= 0 && !st.hasBlockEntity()
				&& (1..steps).all { level.getBlockState(pos.relative(dir, it)).isAir }) {
				level.setBlock(pos.relative(dir, steps), st, 3); level.removeBlock(pos, false); hit = true
			}
		}
		if (hit) { e.deltaMovement = look.scale(-0.8 * k); e.hurtMarked = true; stack.hurtAndBreak(1, e, EquipmentSlot.MAINHAND) }
		level.playSound(null, e.x, e.y, e.z, SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 0.8f, if (charged) 0.7f else 1.2f)
		e.cooldowns.addCooldown(this, if (charged) 30 else 15)
	}
}

object Mats {
	val NOTE: Holder<ArmorMaterial> = Registry.registerForHolder(BuiltInRegistries.ARMOR_MATERIAL, TemplateMod.id("note"),
		ArmorMaterial(ArmorItem.Type.values().associateWith { if (it == ArmorItem.Type.HELMET) 2 else 1 }, 12,
			SoundEvents.ARMOR_EQUIP_IRON, { Ingredient.of(Items.NOTE_BLOCK) }, listOf(ArmorMaterial.Layer(TemplateMod.id("note"))), 1.0f, 0.0f))
}

class WingsItem : Item(Item.Properties().stacksTo(1).durability(432)), Equipable {
	override fun getEquipmentSlot() = EquipmentSlot.CHEST
	override fun use(level: Level, p: Player, h: InteractionHand) = swapWithEquipmentSlot(this, level, p, h)
}

class SackItem : Item(Item.Properties().stacksTo(1)) {
	override fun use(level: Level, p: Player, h: InteractionHand): InteractionResultHolder<ItemStack> {
		if (level is ServerLevel) { if (p.isShiftKeyDown) toggleHelper(level, p) else arm(level, p) }
		return InteractionResultHolder.sidedSuccess(p.getItemInHand(h), level.isClientSide)
	}
	private fun toggleHelper(level: ServerLevel, p: Player) {
		val old = level.getEntitiesOfClass(Helper::class.java, p.boundingBox.inflate(128.0)) { it.ownerId == p.uuid }
		if (old.isNotEmpty()) { old.forEach { it.discard() }; return }
		if ((Rt.helperCd[p.uuid] ?: 0L) > level.gameTime) {
			(p as? ServerPlayer)?.displayClientMessage(Component.translatable("msg.remake.helper_cd"), true); return
		}
		val hp = Helper(ModEntities.HELPER, level); hp.ownerId = p.uuid; hp.moveTo(p.x + 1, p.y, p.z, 0f, 0f); level.addFreshEntity(hp)
	}
	// Вытягивание руки
	private fun arm(level: ServerLevel, p: Player) {
		if (!Rt.ready(p, "arm", 15)) return
		val eye = p.eyePosition; val look = p.lookAngle
		val (bd, _) = Rt.march(level, eye, look, 8.0)
		val t = Rt.firstEntity(level, p, eye, look, bd)
		val len = t?.second ?: bd
		var i = 0.5; while (i < len) { level.sendParticles(ParticleTypes.CRIT, eye.x + look.x * i, eye.y - 0.3 + look.y * i, eye.z + look.z * i, 1, 0.0, 0.0, 0.0, 0.0); i += 0.5 }
		if (t != null && t.first is LivingEntity) { t.first.hurt(level.damageSources().playerAttack(p), 5f); level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1f, 0.8f) }
	}
	companion object {
		// Ненавистная молния: сама выбирает ближайшего врага
		fun lightning(level: ServerLevel, p: ServerPlayer) {
			if (!Rt.ready(p, "bolt", 60)) return
			val t = level.getEntitiesOfClass(LivingEntity::class.java, p.boundingBox.inflate(20.0)) { it is Enemy && it.isAlive && p.hasLineOfSight(it) }
				.minByOrNull { it.distanceToSqr(p) } ?: return
			EntityType.LIGHTNING_BOLT.create(level)?.let { it.moveTo(t.position()); it.setVisualOnly(true); level.addFreshEntity(it) }
			t.hurt(level.damageSources().lightningBolt(), 8f)
		}
	}
}

class HookItem(val tier: Int) : Item(Item.Properties().stacksTo(1)) {
	override fun use(level: Level, p: Player, h: InteractionHand): InteractionResultHolder<ItemStack> {
		if (level is ServerLevel) fire(level, p, p.getItemInHand(h))
		return InteractionResultHolder.sidedSuccess(p.getItemInHand(h), level.isClientSide)
	}
	/** Минимальный уровень крюка, нужный чтобы притянуть цель. */
	private fun need(t: Entity): Int = when {
		t is Player -> t.armorSlots.maxOfOrNull { s ->
			when ((s.item as? ArmorItem)?.material) {
				ArmorMaterials.DIAMOND, ArmorMaterials.NETHERITE -> 5
				ArmorMaterials.IRON, ArmorMaterials.CHAIN, ArmorMaterials.GOLD -> 2
				else -> 1
			}
		} ?: 1
		t is LivingEntity -> if (t.maxHealth > 30f) 5 else 1
		else -> 1
	}
	/** I: сундуки, бочки, шалкеры (без эндер-сундуков); V: любые хранилища, кроме эндер-сундука. */
	private fun storage(st: BlockState, be: BlockEntity?): Boolean {
		if (be == null || st.block is EnderChestBlock) return false
		if (st.block is ChestBlock) return st.getValue(ChestBlock.TYPE) == ChestType.SINGLE
		if (st.block is BarrelBlock || st.block is ShulkerBoxBlock) return true
		return tier >= 5 && be is Container
	}
	private fun fire(level: ServerLevel, p: Player, stack: ItemStack) {
		val eye = p.eyePosition; val look = p.lookAngle
		val range = if (tier >= 4) 32.0 else 20.0
		val power = 1.5 * (1 + 0.2 * Rt.ench(level, stack, "pull_power"))
		val multi = Rt.ench(level, stack, "multishot") > 0
		val red = Rt.ench(level, stack, "redstone_hook"); val hands = Rt.ench(level, stack, "hook_hands") > 0
		// IV: два крюка сразу; мультивыстрел добавляет ещё один
		val dirs = ArrayList<Vec3>()
		if (tier >= 4) { dirs.add(look.yRot(0.18f)); dirs.add(look.yRot(-0.18f)); if (multi) dirs.add(look) }
		else { dirs.add(look); if (multi) dirs.add(look.yRot(0.18f)) }
		var cd = 8; var emergency = false
		val pulled = HashSet<Entity>(); val grapples = ArrayList<Vec3>()
		for (d in dirs) {
			val (bd, bp) = Rt.march(level, eye, d, range)
			val ent = Rt.firstEntity(level, p, eye, d, bd)
			var len = bd
			if (ent != null) {
				val t = ent.first; len = ent.second
				if (!pulled.add(t)) { }
				else if (t is Projectile) { t.deltaMovement = d.scale(2.0); t.owner = p; t.hurtMarked = true }   // перехват снаряда
				else if (t is LivingEntity && t.isFallFlying) cd = maxOf(cd, 20)
				else if (need(t) > tier) { emergency = true; cd = Rt.emergencyCd(level, stack) }            // экстренное притягивание
				else {
					if (t is LivingEntity) t.hurt(level.damageSources().playerAttack(p), 2f)
					t.deltaMovement = p.position().subtract(t.position()).normalize().scale(power).add(0.0, 0.3, 0.0); t.hurtMarked = true
				}
			} else if (bp != null) {
				val st = level.getBlockState(bp); val hs = st.getDestroySpeed(level, bp)
				val face = Direction.getNearest(-d.x, -d.y, -d.z); val hv = eye.add(d.scale(len))
				if (red > 0 && st.`is`(Blocks.REDSTONE_BLOCK)) {                                           // зарядка редстоун-крюка
					Rt.setCharges(stack, red)
					(p as? ServerPlayer)?.displayClientMessage(Component.translatable("msg.remake.charged", red), true)
				} else if (red > 0 && p.isShiftKeyDown && Rt.charges(stack) > 0) {                         // Shift: выпустить редстоун-сигнал
					val sp = bp.relative(face)
					if (level.getBlockState(sp).isAir) {
						level.setBlock(sp, ModBlocks.SIGNAL.defaultBlockState(), 3)
						Rt.setCharges(stack, Rt.charges(stack) - 1)
						(p as? ServerPlayer)?.displayClientMessage(Component.translatable("msg.remake.charges", Rt.charges(stack)), true)
					}
				} else if (hands && st.useWithoutItem(level, bp, p, BlockHitResult(hv, face, bp, false)).consumesAction()) { }  // руки-крюки
				else if (hs < 0 || hs >= 50f) cd = 20                                                       // обсидиан и т.п.
				else if (st.block is FallingBlock) {
					level.removeBlock(bp, false)
					val dest = BlockPos.containing(p.eyePosition.add(look.scale(1.5)))
					if (level.getBlockState(dest).isAir) level.setBlock(dest, st, 3)
				} else if (storage(st, level.getBlockEntity(bp))) {                                         // притягивание хранилищ с содержимым
					val dest = BlockPos.containing(eye.add(look.scale(1.6)))
					if (level.getBlockState(dest).isAir) {
						val be = level.getBlockEntity(bp)!!
						val tag = be.saveWithFullMetadata(level.registryAccess())
						level.removeBlockEntity(bp); level.setBlock(bp, Blocks.AIR.defaultBlockState(), 2)
						level.setBlock(dest, st, 3); level.getBlockEntity(dest)?.loadWithComponents(tag, level.registryAccess())
					}
				} else {
					var broken = false
					val crack = Rt.CRACK[st.block]
					if (tier >= 3 && crack != null) level.setBlock(bp, crack.defaultBlockState(), 3)       // III: повреждённые варианты
					else if (tier >= 3 && hs <= 0.4f && !st.hasBlockEntity()) { level.destroyBlock(bp, true, p); broken = true }
					if (!broken) grapples.add(Vec3.atCenterOf(bp).subtract(eye).normalize())
				}
			}
			var i = 0.5; while (i < len) { level.sendParticles(ParticleTypes.ELECTRIC_SPARK, eye.x + d.x * i, eye.y - 0.2 + d.y * i, eye.z + d.z * i, 1, 0.0, 0.0, 0.0, 0.0); i += 0.75 }
		}
		if (grapples.isNotEmpty()) {
			p.deltaMovement = grapples.reduce { a, b -> a.add(b) }.normalize().scale(power).add(0.0, 0.2, 0.0); p.hurtMarked = true; p.resetFallDistance()
		}
		if (emergency) {
			p.deltaMovement = look.scale(-0.9).add(0.0, 0.3, 0.0); p.hurtMarked = true
			(p as? ServerPlayer)?.displayClientMessage(Component.translatable("msg.remake.emergency"), true)
		}
		level.playSound(null, p.blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 1f, 1.3f)
		p.cooldowns.addCooldown(this, cd)
	}
}

// ───────────── СУЩНОСТИ ─────────────

class ThrownBrick : ThrowableItemProjectile {
	constructor(t: EntityType<out ThrownBrick>, l: Level) : super(t, l)
	constructor(l: Level, o: LivingEntity, s: ItemStack) : super(ModEntities.BRICK, o, l) { item = s }
	override fun getDefaultItem(): Item = Items.BRICK
	override fun onHitEntity(r: EntityHitResult) {
		super.onHitEntity(r)
		r.entity.hurt(damageSources().thrown(this, owner), if (item.`is`(Items.NETHER_BRICK)) 8f else 6f)
	}
	override fun onHit(r: HitResult) {
		super.onHit(r)
		val lvl = level()
		if (lvl is ServerLevel) {
			lvl.sendParticles(ItemParticleOption(ParticleTypes.ITEM, item), x, y, z, 16, 0.1, 0.1, 0.1, 0.15)
			lvl.playSound(null, blockPosition(), SoundEvents.DECORATED_POT_SHATTER, SoundSource.NEUTRAL, 1f, 1.3f)
			discard()
		}
	}
}

class ThrownHead : ThrowableItemProjectile {
	private var back = false
	constructor(t: EntityType<out ThrownHead>, l: Level) : super(t, l)
	constructor(l: Level, o: LivingEntity) : super(ModEntities.HEAD, o, l) { item = ItemStack(Items.WITHER_SKELETON_SKULL); setNoGravity(true) }
	override fun getDefaultItem(): Item = Items.WITHER_SKELETON_SKULL
	override fun tick() {
		super.tick()
		if (level().isClientSide) return
		if (!back && tickCount > 12) back = true
		if (back) {
			val o = owner
			if (o == null || !o.isAlive) { discard(); return }
			val to = o.eyePosition.subtract(position())
			if (to.length() < 1.0 || tickCount > 70) discard() else deltaMovement = to.normalize().scale(0.9)
		}
	}
	override fun onHitEntity(r: EntityHitResult) {
		if (back || r.entity === owner) return
		r.entity.hurt(damageSources().thrown(this, owner), 3f); back = true
	}
	override fun onHit(r: HitResult) {
		if (r.type == HitResult.Type.ENTITY) onHitEntity(r as EntityHitResult) else if (r.type == HitResult.Type.BLOCK) back = true
	}
}

class Helper(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {
	var ownerId: UUID? = null
	private var headCd = 40
	fun owner(): Player? = ownerId?.let { level().getPlayerByUUID(it) }
	override fun registerGoals() {
		goalSelector.addGoal(1, MeleeAttackGoal(this, 1.2, true))
		// бросок головы (возвращается к помощнику)
		goalSelector.addGoal(1, object : Goal() {
			override fun canUse(): Boolean { val t = target ?: return false; return headCd <= 0 && distanceToSqr(t) in 9.0..196.0 && hasLineOfSight(t) }
			override fun start() {
				val t = target ?: return
				val h = ThrownHead(level(), this@Helper); h.setPos(x, eyeY - 0.1, z)
				h.shoot(t.x - x, t.getY(0.5) - eyeY, t.z - z, 1.1f, 0f); level().addFreshEntity(h); headCd = 80
			}
		})
		goalSelector.addGoal(2, object : Goal() {
			init { setFlags(EnumSet.of(Goal.Flag.MOVE)) }
			override fun canUse() = owner()?.let { distanceToSqr(it) > 25.0 } == true
			override fun tick() {
				val o = owner() ?: return
				if (distanceToSqr(o) > 400.0) moveTo(o.x, o.y, o.z) else getNavigation().moveTo(o, 1.3)
			}
		})
		goalSelector.addGoal(3, RandomLookAroundGoal(this))
	}
	override fun customServerAiStep() {
		if (headCd > 0) headCd--
		val o = owner() ?: return
		if (target == null || !target!!.isAlive) {
			val t = o.lastHurtMob ?: o.lastHurtByMob
			if (t != null && t.isAlive && t !is Helper && t !== o) target = t
		}
	}
	override fun die(src: DamageSource) { super.die(src); ownerId?.let { Rt.helperCd[it] = level().gameTime + 600 } }  // 30 сек
	override fun removeWhenFarAway(d: Double) = false
	override fun addAdditionalSaveData(tag: CompoundTag) { super.addAdditionalSaveData(tag); ownerId?.let { tag.putUUID("Owner", it) } }
	override fun readAdditionalSaveData(tag: CompoundTag) { super.readAdditionalSaveData(tag); if (tag.hasUUID("Owner")) ownerId = tag.getUUID("Owner") }
	companion object {
		fun attrs() = Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 10.0).add(Attributes.MOVEMENT_SPEED, 0.32).add(Attributes.ATTACK_DAMAGE, 3.0)
	}
}

object ModEntities {
	val BRICK: EntityType<ThrownBrick> = Registry.register(BuiltInRegistries.ENTITY_TYPE, TemplateMod.id("brick"),
		EntityType.Builder.of({ t: EntityType<ThrownBrick>, l: Level -> ThrownBrick(t, l) }, MobCategory.MISC).sized(0.25f, 0.25f).clientTrackingRange(4).updateInterval(10).build("brick"))
	val HELPER: EntityType<Helper> = Registry.register(BuiltInRegistries.ENTITY_TYPE, TemplateMod.id("helper"),
		EntityType.Builder.of({ t: EntityType<Helper>, l: Level -> Helper(t, l) }, MobCategory.CREATURE).sized(0.6f, 1.8f).build("helper"))
	val HEAD: EntityType<ThrownHead> = Registry.register(BuiltInRegistries.ENTITY_TYPE, TemplateMod.id("helper_head"),
		EntityType.Builder.of({ t: EntityType<ThrownHead>, l: Level -> ThrownHead(t, l) }, MobCategory.MISC).sized(0.4f, 0.4f).clientTrackingRange(6).updateInterval(2).build("helper_head"))
	fun init() { FabricDefaultAttributeRegistry.register(HELPER, Helper.attrs()) }
}

// ───────────── БЛОКИ ─────────────

private val HF = BlockStateProperties.HORIZONTAL_FACING
private val FACING = BlockStateProperties.FACING
private val POWERED = BlockStateProperties.POWERED

class ConveyorBlock(p: BlockBehaviour.Properties) : Block(p) {
	init { registerDefaultState(stateDefinition.any().setValue(HF, Direction.NORTH)) }
	override fun codec(): MapCodec<out Block> = simpleCodec { ConveyorBlock(it) }
	override fun createBlockStateDefinition(b: StateDefinition.Builder<Block, BlockState>) { b.add(HF) }
	override fun getStateForPlacement(c: BlockPlaceContext): BlockState? = defaultBlockState().setValue(HF, c.horizontalDirection)
	override fun stepOn(level: Level, pos: BlockPos, state: BlockState, e: Entity) {
		val d = state.getValue(HF)
		e.deltaMovement = e.deltaMovement.add(d.stepX * 0.07, 0.0, d.stepZ * 0.07)
	}
}

class SpringBlock(p: BlockBehaviour.Properties) : Block(p) {
	init { registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP)) }
	override fun codec(): MapCodec<out Block> = simpleCodec { SpringBlock(it) }
	override fun createBlockStateDefinition(b: StateDefinition.Builder<Block, BlockState>) { b.add(FACING) }
	// FACING = куда пружина стреляет; крепится к противоположной стороне
	override fun getStateForPlacement(c: BlockPlaceContext): BlockState? = defaultBlockState().setValue(FACING, c.clickedFace)
	override fun getShape(s: BlockState, l: BlockGetter, p: BlockPos, c: CollisionContext): VoxelShape = when (s.getValue(FACING)) {
		Direction.UP -> box(0.0, 0.0, 0.0, 16.0, 4.0, 16.0)
		Direction.DOWN -> box(0.0, 12.0, 0.0, 16.0, 16.0, 16.0)
		Direction.NORTH -> box(0.0, 0.0, 12.0, 16.0, 16.0, 16.0)
		Direction.SOUTH -> box(0.0, 0.0, 0.0, 16.0, 16.0, 4.0)
		Direction.EAST -> box(0.0, 0.0, 0.0, 4.0, 16.0, 16.0)
		else -> box(12.0, 0.0, 0.0, 16.0, 16.0, 16.0)
	}
	override fun entityInside(s: BlockState, level: Level, pos: BlockPos, e: Entity) {
		val d = s.getValue(FACING); val v = e.deltaMovement; val k = 1.4
		e.deltaMovement = Vec3(if (d.stepX != 0) d.stepX * k else v.x, if (d.stepY != 0) d.stepY * k else v.y, if (d.stepZ != 0) d.stepZ * k else v.z)
		e.resetFallDistance()
		if (!level.isClientSide) { e.hurtMarked = true; if (e.tickCount % 6 == 0) level.playSound(null, pos, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 1f, 1.5f) }
	}
}

class StrongPistonBlock(p: BlockBehaviour.Properties) : Block(p) {
	companion object { val RANGE: IntegerProperty = IntegerProperty.create("range", 2, 5) }
	init { registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false).setValue(RANGE, 2)) }
	override fun codec(): MapCodec<out Block> = simpleCodec { StrongPistonBlock(it) }
	override fun createBlockStateDefinition(b: StateDefinition.Builder<Block, BlockState>) { b.add(FACING, POWERED, RANGE) }
	override fun getStateForPlacement(c: BlockPlaceContext): BlockState? = defaultBlockState().setValue(FACING, c.nearestLookingDirection.opposite)
	// ПКМ пустой рукой — дальность 2..5
	override fun useWithoutItem(s: BlockState, level: Level, pos: BlockPos, pl: Player, hit: BlockHitResult): InteractionResult {
		if (!level.isClientSide) {
			val n = if (s.getValue(RANGE) >= 5) 2 else s.getValue(RANGE) + 1
			level.setBlock(pos, s.setValue(RANGE, n), 3)
			pl.displayClientMessage(Component.translatable("msg.remake.range", n), true)
		}
		return InteractionResult.sidedSuccess(level.isClientSide)
	}
	override fun neighborChanged(s: BlockState, level: Level, pos: BlockPos, b: Block, from: BlockPos, moving: Boolean) {
		if (level !is ServerLevel) return
		val pw = level.hasNeighborSignal(pos)
		if (pw != s.getValue(POWERED)) { level.setBlock(pos, s.setValue(POWERED, pw), 2); if (pw) push(level, pos, s) }
	}
	private fun push(level: ServerLevel, pos: BlockPos, s: BlockState) {
		val dir = s.getValue(FACING); val n = s.getValue(RANGE)
		val line = ArrayList<BlockPos>(); var c = pos.relative(dir)
		while (!level.getBlockState(c).isAir) {
			val st = level.getBlockState(c)
			if (line.size >= 12 || st.getDestroySpeed(level, c) < 0 || st.pushReaction != PushReaction.NORMAL || st.hasBlockEntity()) return
			line.add(c); c = c.relative(dir)
		}
		val last = line.lastOrNull() ?: pos
		var d = 0; while (d < n && level.getBlockState(last.relative(dir, d + 1)).isAir) d++
		if (line.isNotEmpty() && d == 0) return
		val box = AABB(pos.relative(dir)).expandTowards(dir.stepX * n.toDouble(), dir.stepY * n.toDouble(), dir.stepZ * n.toDouble())
		for (e in level.getEntitiesOfClass(Entity::class.java, box)) { e.deltaMovement = Vec3(dir.stepX * 0.5 * n, dir.stepY * 0.5 * n + 0.2, dir.stepZ * 0.5 * n); e.hurtMarked = true }
		for (b in line.asReversed()) {
			val st = level.getBlockState(b)
			level.setBlock(b.relative(dir, d), st, 3)
			level.setBlock(b, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2)
		}
		level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1f, 0.7f)
	}
}

class ShooterBlock(p: BlockBehaviour.Properties) : Block(p) {
	init { registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false)) }
	override fun codec(): MapCodec<out Block> = simpleCodec { ShooterBlock(it) }
	override fun createBlockStateDefinition(b: StateDefinition.Builder<Block, BlockState>) { b.add(FACING, POWERED) }
	override fun getStateForPlacement(c: BlockPlaceContext): BlockState? = defaultBlockState().setValue(FACING, c.nearestLookingDirection.opposite)
	override fun neighborChanged(s: BlockState, level: Level, pos: BlockPos, b: Block, from: BlockPos, moving: Boolean) {
		if (level !is ServerLevel) return
		val pw = level.hasNeighborSignal(pos)
		if (pw != s.getValue(POWERED)) { level.setBlock(pos, s.setValue(POWERED, pw), 2); if (pw) shoot(level, pos, s.getValue(FACING)) }
	}
	// Выстрел летит до первой стены
	private fun shoot(level: ServerLevel, pos: BlockPos, d: Direction) {
		val dv = Vec3(d.stepX.toDouble(), d.stepY.toDouble(), d.stepZ.toDouble())
		val from = Vec3.atCenterOf(pos).add(dv.scale(0.6))
		val (dist, _) = Rt.march(level, from, dv, 48.0)
		val end = from.add(dv.scale(dist))
		for (e in level.getEntitiesOfClass(LivingEntity::class.java, AABB(from, end).inflate(0.4))) e.hurt(level.damageSources().generic(), 6f)
		var i = 0.0; while (i <= dist) { level.sendParticles(ParticleTypes.END_ROD, from.x + dv.x * i, from.y + dv.y * i, from.z + dv.z * i, 1, 0.0, 0.0, 0.0, 0.0); i += 0.5 }
		level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.BLOCKS, 1f, 1.4f)
	}
}

/** Невидимый блок: на 12 тиков даёт редстоун-сигнал (для редстоун-крюка). */
class SignalBlock(p: BlockBehaviour.Properties) : Block(p) {
	override fun codec(): MapCodec<out Block> = simpleCodec { SignalBlock(it) }
	override fun getRenderShape(s: BlockState) = RenderShape.INVISIBLE
	override fun getShape(s: BlockState, l: BlockGetter, p: BlockPos, c: CollisionContext): VoxelShape = Shapes.empty()
	override fun isSignalSource(s: BlockState) = true
	override fun getSignal(s: BlockState, g: BlockGetter, p: BlockPos, d: Direction) = 15
	override fun getDirectSignal(s: BlockState, g: BlockGetter, p: BlockPos, d: Direction) = 15
	override fun onPlace(s: BlockState, level: Level, pos: BlockPos, old: BlockState, moving: Boolean) { level.scheduleTick(pos, this, 12) }
	override fun tick(s: BlockState, level: ServerLevel, pos: BlockPos, r: RandomSource) { level.removeBlock(pos, false) }
}

object ModBlocks {
	private fun <T : Block> reg(n: String, b: T): T = Registry.register(BuiltInRegistries.BLOCK, TemplateMod.id(n), b)
	private fun props() = BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL)
	val CONVEYOR = reg("conveyor", ConveyorBlock(props()))
	val SPRING = reg("spring", SpringBlock(props().noOcclusion()))
	val STRONG_PISTON = reg("strong_piston", StrongPistonBlock(props()))
	val SHOOTER = reg("shooter", ShooterBlock(props()))
	val SIGNAL = reg("signal", SignalBlock(BlockBehaviour.Properties.of().noCollission().noOcclusion().instabreak().noLootTable()))
}

object ModItems {
	private fun <T : Item> reg(n: String, i: T): T = Registry.register(BuiltInRegistries.ITEM, TemplateMod.id(n), i)
	val CLAWS = reg("claws", ClawsItem())
	val HAND_PISTON = reg("hand_piston", HandPistonItem())
	val NOTE_HELMET = reg("note_helmet", ArmorItem(Mats.NOTE, ArmorItem.Type.HELMET, Item.Properties().durability(ArmorItem.Type.HELMET.getDurability(24))))
	val SACK = reg("hate_sack", SackItem())
	val WINGS = reg("electro_wings", WingsItem())
	val HOOKS = (1..5).map { reg("hook_$it", HookItem(it)) }
	val CONVEYOR = reg("conveyor", BlockItem(ModBlocks.CONVEYOR, Item.Properties()))
	val SPRING = reg("spring", BlockItem(ModBlocks.SPRING, Item.Properties()))
	val STRONG_PISTON = reg("strong_piston", BlockItem(ModBlocks.STRONG_PISTON, Item.Properties()))
	val SHOOTER = reg("shooter", BlockItem(ModBlocks.SHOOTER, Item.Properties()))
	val TAB: CreativeModeTab = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TemplateMod.id("main"),
		FabricItemGroup.builder().title(Component.translatable("itemGroup.remake")).icon { ItemStack(HOOKS[0]) }
			.displayItems { _, out ->
				listOf<Item>(CLAWS, HAND_PISTON, NOTE_HELMET, SACK, WINGS).plus(HOOKS).plus(listOf(CONVEYOR, SPRING, STRONG_PISTON, SHOOTER)).forEach { out.accept(it) }
			}.build())
	fun init() {}
}
