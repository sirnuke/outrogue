package com.degrendel.outrogue.common

sealed class Action
{
  abstract val creature: Creature
}

data class Sleep(override val creature: Creature): Action()
data class Move(override val creature: Creature, val direction: EightWay): Action()
//data class MeleeAttack
//data class RangedAttack
//data class UseItem
//data class ReadScroll
//data class PickupItem
//data class DropItem
//data class EquipItem
