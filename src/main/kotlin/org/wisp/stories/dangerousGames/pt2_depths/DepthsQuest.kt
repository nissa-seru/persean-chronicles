package org.wisp.stories.dangerousGames.pt2_depths

import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.PlanetAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.Conditions
import com.fs.starfarer.api.impl.campaign.ids.Drops
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.MemFlags
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.SalvageEntity
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.util.WeightedRandomPicker
import org.wisp.stories.dangerousGames.pt1_dragons.DragonsQuest
import org.wisp.stories.game
import wisp.questgiver.AutoQuestFacilitator
import wisp.questgiver.InteractionDefinition
import wisp.questgiver.starSystemsAllowedForQuests
import wisp.questgiver.wispLib.*

/**
 * Bring some passengers to find treasure on an ocean floor. Solve riddles to keep them alive.
 */
object DepthsQuest : AutoQuestFacilitator(
    stageBackingField = PersistentData(key = "depthsQuestStage", defaultValue = { Stage.NotStarted }),
    autoIntelInfo = AutoIntelInfo(DepthsQuest_Intel::class.java) {
        DepthsQuest_Intel(
            DepthsQuest.state.startingPlanet,
            DepthsQuest.state.depthsPlanet
        )
    },
    autoBarEventInfo = AutoBarEventInfo(
        barEventCreator = Depths_Stage1_BarEventCreator(),
        shouldGenerateBarEvent = {
            DragonsQuest.stage == DragonsQuest.Stage.Done
                    && game.sector.clock.getElapsedDaysSince(DragonsQuest.state.startDate ?: 0) >= 30
        },
        shouldOfferFromMarket = { market ->
            market.factionId.toLowerCase() !in listOf("luddic_church", "luddic_path")
                    && market.starSystem != null // No prism freeport
                    && market.size > 4
                    && DepthsQuest.state.depthsPlanet != null
        }
    )
) {
    private val DEPTHS_PLANET_TYPES = listOf(
        "terran",
        "terran-eccentric",
        "water",
        "US_water", // Unknown Skies
        "US_waterB", // Unknown Skies
        "US_continent" // Unknown Skies
    )

    val icon = InteractionDefinition.Portrait(category = "wispStories_depths", id = "icon")
    val diveIllustration = InteractionDefinition.Illustration(category = "wispStories_depths", id = "diveIllustration")
    val intelIllustration =
        InteractionDefinition.Illustration(category = "wispStories_depths", id = "intelIllustration")

    const val rewardCredits: Int = 100000 // TODO
    const val minimumDistanceFromPlayerInLightYearsToPlaceDepthsPlanet = 5


    val state = State(PersistentMapData<String, Any?>(key = "depthsState").withDefault { null })

    class State(val map: MutableMap<String, Any?>) {
        var startDate: Long? by map
        var depthsPlanet: SectorEntityToken? by map
        var startingPlanet: SectorEntityToken? by map
    }

    val choices: Choices =
        Choices(PersistentMapData<String, Any?>(key = "depthsChoices").withDefault { null })

    /**
     * All choices that can be made.
     * Leave `map` public and accessible so it can be cleared if the quest is restarted.
     */
    class Choices(val map: MutableMap<String, Any?>) {
        var riddle1Choice: Depths_Stage2_RiddleDialog.RiddleChoice.Riddle1Choice? by map
        var riddle2Choice: Depths_Stage2_RiddleDialog.RiddleChoice.Riddle2Choice? by map
        var riddle3Choice: Depths_Stage2_RiddleDialog.RiddleChoice.Riddle3Choice? by map
    }

    val riddleAnswers
        get() = listOf(choices.riddle1Choice, choices.riddle2Choice, choices.riddle3Choice)

    val riddleSuccessesCount: Int
        get() = riddleAnswers.count { it?.wasSuccessful() == true }

    val wallCrashesCount: Int
        get() = (if (choices.riddle1Choice is Depths_Stage2_RiddleDialog.RiddleChoice.Riddle1Choice.WestWall) 1 else 0) +
                (if (choices.riddle2Choice is Depths_Stage2_RiddleDialog.RiddleChoice.Riddle2Choice.WestWall) 1 else 0) +
                (if (choices.riddle3Choice is Depths_Stage2_RiddleDialog.RiddleChoice.Riddle3Choice.EastWall) 1 else 0)

    val didAllCrewDie: Boolean
        get() = riddleAnswers.all { it?.wasSuccessful() == false }

    override fun updateTextReplacements(text: Text) {
        text.globalReplacementGetters["depthsSourcePlanet"] = { state.startingPlanet?.name }
        text.globalReplacementGetters["depthsSourceSystem"] = { state.startingPlanet?.starSystem?.name }
        text.globalReplacementGetters["depthsPlanet"] = { state.depthsPlanet?.name }
        text.globalReplacementGetters["depthsSystem"] = { state.depthsPlanet?.starSystem?.name }
        text.globalReplacementGetters["depthsCreditReward"] = { Misc.getDGSCredits(rewardCredits.toFloat()) }
    }

    override fun regenerateQuest(interactionTarget: SectorEntityToken, market: MarketAPI?) {
        state.startingPlanet = interactionTarget
        findAndTagDepthsPlanet(interactionTarget.starSystem)
    }

    fun restartQuest() {
        game.logger.i { "Restarting Depths quest." }

        state.map.clear()
        choices.map.clear()
        stage = Stage.NotStarted
        game.sector.starSystems.flatMap { it.solidPlanets }
            .filter { it.market?.hasCondition(CrystalMarketMod.CONDITION_ID) == true }
            .forEach { it.market?.removeCondition(CrystalMarketMod.CONDITION_ID) }
    }

    fun startStage1() {
        stage = Stage.GoToPlanet
        state.startDate = game.sector.clock.timestamp
    }

    fun startStart2() {
        stage = Stage.ReturnToStart
        game.intelManager.findFirst(DepthsQuest_Intel::class.java)
            ?.apply {
                flipStartAndEndLocations()
                sendUpdateIfPlayerHasIntel(null, false)
            }
    }

    fun startMusic() {
        kotlin.runCatching {
            game.soundPlayer.playCustomMusic(
                0,
                5,
                "wisp_perseanchronicles_depthsMusic",
                true
            )
        }
            .onFailure {
                game.logger.e(it)
            }
    }

    fun stopMusic() {
        kotlin.runCatching {
            game.soundPlayer.playCustomMusic(3, 0, null)
        }
            .onFailure {
                game.logger.e(it)
            }
    }

    fun finishQuest() {
        game.sector.playerFleet.cargo.credits.add(rewardCredits.toFloat())
        stage = Stage.Done

        state.depthsPlanet?.let { planet ->
            if (planet.market.conditions.none { it.plugin is CrystalMarketMod }) {
                planet.market.addCondition("wispQuests_crystallineCatalyst")
            }
        }
    }

    fun generateRewardLoot(entity: SectorEntityToken): CargoAPI? {
        when (riddleSuccessesCount) {
            3 -> {
                entity.addDropValue(Drops.BASIC, 50000)
                entity.addDropRandom("blueprints", 5)
                entity.addDropRandom("rare_tech", 2)
            }
            2 -> {
                entity.addDropValue(Drops.BASIC, 30000)
                entity.addDropRandom("blueprints", 4)
                entity.addDropRandom("rare_tech", 1)
            }
            1 -> {
                entity.addDropValue(Drops.BASIC, 20000)
                entity.addDropRandom("blueprints", 3)
                entity.addDropRandom("rare_tech", 1)
            }
            else -> {
                entity.addDropValue(Drops.BASIC, 10000)
                entity.addDropRandom("blueprints", 2)
                entity.addDropRandom("rare_tech", 1)
            }
        }

        return SalvageEntity.generateSalvage(
            Misc.getRandom(game.sector.memoryWithoutUpdate.getLong(MemFlags.SALVAGE_SEED), 100),
            1f,
            1f,
            1f,
            1f,
            entity.dropValue,
            entity.dropRandom
        )
            .apply { sort() }
    }

    /**
     * Find a planet with oceans somewhere near the center, excluding player's current location.
     * Prefer decivilized world, then uninhabited, then all others
     */
    private fun findAndTagDepthsPlanet(playersCurrentStarSystem: StarSystemAPI?) {
        val planet = try {
            game.sector.starSystemsAllowedForQuests
                .filter { it.id != playersCurrentStarSystem?.id }
                .filter { system -> system.solidPlanets.any { planet -> planet.typeId in DEPTHS_PLANET_TYPES } }
                .prefer { it.distanceFromPlayerInHyperspace > minimumDistanceFromPlayerInLightYearsToPlaceDepthsPlanet }
                .sortedBy { it.distanceFromCenterOfSector }
                .flatMap { it.solidPlanets }
                .prefer { it.faction.id == Factions.NEUTRAL } // Uncolonized planets
                .filter { planet -> planet.typeId in DEPTHS_PLANET_TYPES }
                .toList()
                .run {
                    // Take all planets from the top half of the list,
                    // which is sorted by proximity to the center.
                    val possibles = this.take((this.size / 2).coerceAtLeast(1))

                    WeightedRandomPicker<PlanetAPI>().apply {
                        possibles.forEach { planet ->
                            when {
                                planet.market?.hasCondition(Conditions.DECIVILIZED) == true -> {
                                    game.logger.i { "Adding decivved planet ${planet.fullName} in ${planet.starSystem.baseName} to Depths candidate list" }
                                    add(planet, 3f)
                                }
                                planet.market?.size == 0 -> {
                                    game.logger.i { "Adding uninhabited planet ${planet.fullName} in ${planet.starSystem.baseName} to Depths candidate list" }
                                    add(planet, 2f)
                                }
                                else -> {
                                    game.logger.i { "Adding planet ${planet.fullName} in ${planet.starSystem.baseName} to Depths candidate list" }
                                    add(planet, 1f)
                                }
                            }
                        }
                    }
                        .pick()!!
                }
        } catch (e: Exception) {
            // If no planets matching the criteria are found
            game.logger.i(e) { "No planets matching the criteria found for Depths" }
            return
        }

        game.logger.i { "Set Depths quest destination to ${planet.fullName} in ${planet.starSystem.baseName}" }
        state.depthsPlanet = planet
    }

    abstract class Stage(progress: Progress) : AutoQuestFacilitator.Stage(progress) {
        object NotStarted : Stage(Progress.NotStarted)
        object GoToPlanet : Stage(Progress.InProgress)
        object ReturnToStart : Stage(Progress.InProgress)
        object Done : Stage(Progress.Completed)
    }
}