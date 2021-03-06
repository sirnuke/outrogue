package com.degrendel.outrogue.engine.world

import com.badlogic.ashley.core.Entity
import com.degrendel.outrogue.common.components.*
import com.degrendel.outrogue.common.logger
import com.degrendel.outrogue.common.world.*
import com.degrendel.outrogue.common.world.Level.Companion.floorRange
import com.degrendel.outrogue.common.world.creatures.Conjurer
import com.degrendel.outrogue.common.world.creatures.Creature
import com.degrendel.outrogue.common.world.creatures.Rogue
import com.degrendel.outrogue.engine.EngineState

class WorldState(val engine: EngineState) : World
{
  companion object
  {
    private val L by logger()
    const val ATTACK_ROLL = 20
  }

  private val levels: List<LevelState>

  init
  {
    // TODO: Ick
    var previous: LevelState? = null
    levels = floorRange.map { previous = LevelState(it, previous, engine); previous!! }
  }

  private val _conjurer: ConjurerState
  private var _rogue: RogueState

  init
  {
    levels[0].let { level ->
      level.getRandomRooms(2).let { rooms ->
        assert(rooms.size == 2)
        // TODO: Filter to avoid staircases
        _conjurer = ConjurerState(engine, Entity(), rooms[0].getRandomSquare { true }!!)
            .also { level.spawn(it) }
        _rogue = RogueState(engine, this, Entity(), rooms[1].getRandomSquare { true }!!, 0L)
            .also { level.spawn(it) }
      }
    }

    levels.forEach { it.populate(this) }
  }

  override val conjurer: Conjurer get() = _conjurer
  override val rogue: Rogue get() = _rogue

  override fun getLevel(floor: Int): Level = levels[floor]
  override fun getLevel(coordinate: Coordinate): Level = levels[coordinate.floor]
  override fun getSquare(coordinate: Coordinate) = levels[coordinate.floor].getSquare(coordinate)
  override fun getSquare(x: Int, y: Int, floor: Int) = levels[floor].getSquare(x, y)

  fun getLevelState(creature: Creature) = levels[creature.coordinate.floor]

  fun bootstrapECS()
  {
    levels.forEach { it.bootstrapECS(engine.ecs, conjurer.coordinate.floor) }
  }

  fun setVisibleFloor(floor: Int)
  {
    levels.forEach { it.setOnVisibleLevel(floor == it.floor) }
  }

  fun navigateStaircase(creature: CreatureState, descending: Boolean)
  {
    val currentLevel = getLevelState(creature)
    val staircase = currentLevel.getSquare(creature.coordinate).staircase!!
    if (!descending && currentLevel.isFirst)
      TODO("Need to implement leaving the dungeon")
    val newLevel = if (descending)
      levels[creature.coordinate.floor + 1]
    else
      levels[creature.coordinate.floor - 1]
    val landing = if (descending)
      newLevel.staircasesUp[staircase].coordinate
    else
      newLevel.staircasesDown[staircase].coordinate
    currentLevel.despawn(creature)
    creature.move(landing)
    newLevel.spawn(creature)
    if (creature == conjurer)
      setVisibleFloor(newLevel.floor)
  }

  fun swapCreatures(first: CreatureState, second: CreatureState)
  {
    when (first.coordinate.floor - second.coordinate.floor)
    {
      0 ->
      {
        assert(first.coordinate.canInteract(this, second.coordinate))
        levels[first.coordinate.floor].swapCreatures(first, second)
      }
      1, -1 ->
      {
        // TODO: Meh
        val firstLevel = getLevelState(first)
        val secondLevel = getLevelState(second)
        val firstSquare = firstLevel.getSquare(first.coordinate)
        val secondSquare = secondLevel.getSquare(second.coordinate)
        assert(firstSquare.staircase != null)
        assert(secondSquare.staircase != null)
        assert(firstSquare.staircase == secondSquare.staircase)
        firstLevel.despawn(first)
        secondLevel.despawn(second)
        first.move(secondSquare.coordinate)
        second.move(firstSquare.coordinate)
        firstLevel.spawn(second)
        secondLevel.spawn(first)
      }
      else -> throw IllegalArgumentException("Creatures $first and $second are more than one floor apart!")
    }
  }

  fun computeVisibleAndKnown()
  {
    // TODO: Ick, also this algorithm might be a bit slow - there's potentially a ton of things being touched.
    //       This is especially true if RogueTeam gets huge and spreads out around the map

    // With list of all friends, compute set<Coordinate> of eightway neighbors plus friends
    val visible = mutableSetOf<Coordinate>()
    // Also record what rooms are visible from the tiles of RogueFriends
    val rooms = mutableSetOf<Pair<Int, Int>>()
    engine.ecs.getEntitiesFor(engine.rogueTeam).forEach {
      it.getCreature().coordinate.let { coordinate ->
        rooms.addAll(getSquare(coordinate).visible.map { id -> Pair(coordinate.floor, id) })
        visible.add(coordinate)
        visible.addAll(coordinate.eightWayNeighbors())
      }
    }
    // Iterate through the visible rooms, add them to visible
    rooms.forEach { (floor, id) ->
      levels[floor].getRoomState(id).walkable.forEach { visible.add(it) }
    }
    // Iterate through all visible, if not in the set remove visible component
    // (Convert asSequence.asIterable to duplicate the array since it messes with the iterators otherwise :/
    engine.ecs.getEntitiesFor(engine.squaresVisibleToRogue).asSequence().asIterable().forEach {
      if (it.getCoordinate() !in visible)
        (it.getSquare() as SquareState).setRogueVisible(false)
    }
    // For each currently visible thing, if not in set<Coordinate> remove visible component
    engine.ecs.getEntitiesFor(engine.creaturesVisibleToRogue).asSequence().asIterable().forEach {
      // TODO: Replace this with a helper
      if (it.getCoordinate() !in visible)
        it.remove(VisibleToRogueComponent::class.java)
    }
    // Mark each coordinate as visible and known.  If there's a creature there, also mark it as visible
    // TODO: Will need to do this with things as well
    visible.forEach {
      levels[it.floor].getSquareState(it).let { square ->
        if (square.type.blocked) return@let
        square.setRogueVisible(true)
        // TODO: Replace this with a a helper
        square.creature?.entity?.add(VisibleToRogueComponent)?.add(KnownToRogueComponent)
      }
    }
  }

  override fun canMoveCheckingCreatures(from: Coordinate, direction: EightWay) = canMoveCheckingCreatures(from, from.move(direction))
  override fun canMoveIgnoringCreatures(from: Coordinate, direction: EightWay) = canMoveIgnoringCreatures(from, from.move(direction))
  override fun canMoveCheckingCreatures(from: Coordinate, to: Coordinate) = canMove(from, to, true)
  override fun canMoveIgnoringCreatures(from: Coordinate, to: Coordinate) = canMove(from, to, false)

  private fun canMove(from: Coordinate, to: Coordinate, checkCreatures: Boolean): Boolean
  {
    val square = getSquare(to)
    return (to.isValid()
        && (!checkCreatures || square.creature == null)
        && !square.type.blocked
        && from.canInteract(this, to))
  }

  fun performMeleeAttack(attacker: CreatureState, target: CreatureState): AttackResult
  {
    // TODO: Eventually want to model dodge versus penetration; right now combined
    val chance = engine.random.nextInt(ATTACK_ROLL) + 1
    val need = ATTACK_ROLL + target.armor.ac - attacker.weapon.toHit - attacker.toHit
    // TODO: Should this be >= or >?
    return if (chance >= need)
    {
      val damage = attacker.weapon.getDamage()
      return when (target.applyDamage(damage))
      {
        DamageResult.SUSTAINED -> MeleeLandedResult(attacker, target, damage)
        DamageResult.DEFEATED -> MeleeDefeatedResult(attacker, target)
      }
    }
    else
      MeleeMissedResult(attacker, target)
  }
}