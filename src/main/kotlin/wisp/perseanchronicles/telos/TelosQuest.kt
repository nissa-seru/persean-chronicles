package wisp.perseanchronicles.telos

import com.fs.starfarer.api.campaign.PlanetAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.Commodities
import com.fs.starfarer.api.impl.campaign.ids.Conditions
import com.fs.starfarer.api.impl.campaign.ids.StarTypes
import com.fs.starfarer.api.impl.campaign.ids.Terrain
import com.fs.starfarer.api.impl.campaign.procgen.PlanetGenDataSpec
import com.fs.starfarer.api.impl.campaign.procgen.StarGenDataSpec
import com.fs.starfarer.api.impl.campaign.terrain.StarCoronaTerrainPlugin
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import wisp.perseanchronicles.MOD_ID
import wisp.perseanchronicles.game
import wisp.perseanchronicles.telos.pt1_deliveryToEarth.Telos_Stage1_BarEventCreator
import wisp.questgiver.*
import wisp.questgiver.wispLib.*
import kotlin.random.Random

object TelosQuest : AutoQuestFacilitator(
    stageBackingField = PersistentData(key = "telosStage", defaultValue = { Stage.NotStarted }),
    autoBarEventInfo = AutoBarEventInfo(
        barEventCreator = Telos_Stage1_BarEventCreator(),
        shouldGenerateBarEvent = { true },
        shouldOfferFromMarket = { market ->
            true
//            market.factionId.toLowerCase() in listOf(Factions.INDEPENDENT.toLowerCase())
//                    && market.starSystem != null // No prism freeport
//                    && market.size > 3
//                    && TelosQuest.state.destPlanet != null
        }),
    autoIntelInfo = null
//    AutoIntelInfo(TelosIntel::class.java) {
//        TelosIntel(TelosQuest.state.startLocation, TelosQuest.state.destPlanet)
//    }
) {
    val REWARD_CREDITS: Float
        get() = Questgiver.calculateCreditReward(state.startLocation, state.destPlanet, scaling = 1.3f)
    const val CARGO_TYPE = Commodities.HEAVY_MACHINERY
    const val CARGO_WEIGHT = 5

    //    val icon = InteractionDefinition.Portrait(category = "wisp_perseanchronicles_telos", id = "davidRengel")
    val background = InteractionDefinition.Illustration(category = "wisp_perseanchronicles_telos", id = "background")

    val state = State(PersistentMapData<String, Any?>(key = "telosState").withDefault { null })

    class State(val map: MutableMap<String, Any?>) {
        /**
         * In millis.
         */
        var startDate: Long? by map
        var startLocation: SectorEntityToken? by map
        var destPlanet: SectorEntityToken? by map
        var completeDateInMillis: Long? by map
        var secretCompleteDateInMillis: Long? by map

        val destSystem: StarSystemAPI?
            get() = destPlanet?.starSystem
    }

    override fun updateTextReplacements(text: Text) {
        text.globalReplacementGetters["telosCredits"] = { Misc.getDGSCredits(REWARD_CREDITS) }
        text.globalReplacementGetters["telosDestPlanet"] = { state.destPlanet?.name }
        text.globalReplacementGetters["telosDestSystem"] = { state.destSystem?.name }
        text.globalReplacementGetters["telosCargoTons"] = { CARGO_WEIGHT.toString() }
        text.globalReplacementGetters["telosStarName"] = { state.destPlanet?.starSystem?.star?.name }
    }

    override fun regenerateQuest(interactionTarget: SectorEntityToken, market: MarketAPI?) {
        state.startLocation = interactionTarget

        val system = game.sector.starSystemsAllowedForQuests
            .filter { sys -> sys.star?.spec?.isPulsar == true }
            .prefer { it.distanceFromPlayerInHyperspace >= 18 } // 18+ LY away
            .ifEmpty {
                createPulsarSystem()
                regenerateQuest(interactionTarget, market)
                return
            }
            .let { pulsarSystems ->
                val pulsarSystemsWithPlanet =
                    pulsarSystems
                        .filter { sys -> sys.solidPlanets.any { isValidPlanetForDestination(it) } }

                return@let if (pulsarSystemsWithPlanet.isEmpty()) {
                    val system = pulsarSystems.random()
                    addPlanetToSystem(system)
                    system
                } else {
                    pulsarSystemsWithPlanet
                        .prefer { system ->
                            system.solidPlanets.any { planet -> planet.faction?.isHostileTo(game.sector.playerFaction) != true }
                        }
                        .random()
                }
            }

        val planet = system.solidPlanets
            .filter { isValidPlanetForDestination(it) }
            .prefer { planet ->
                planet.faction?.isHostileTo(game.sector.playerFaction) != true
            }
            .minByOrNull { it.market?.hazardValue ?: 500f }
            ?: kotlin.run {
                // This should never happen, the system should be generated by this point.
                game.errorReporter.reportCrash(NullPointerException("No planet found in ${system.name} for Telos quest."))
                return
            }

        // Change the planet to be tidally locked so there's a realistic place to set up a base camp.
        planet.spec.rotation = 0f
        planet.applySpecChanges()

        state.destPlanet = planet
    }

    fun start(startLocation: SectorEntityToken) {
        game.logger.i { "Telos start location set to ${startLocation.fullName} in ${startLocation.starSystem.baseName}" }
        stage = Stage.GoToPlanet
        game.sector.playerFleet.cargo.addCommodity(CARGO_TYPE, CARGO_WEIGHT.toFloat())
        state.startDate = game.sector.clock.timestamp
    }

    fun shouldShowStage2Dialog() =
        stage == Stage.GoToPlanet
                && game.sector.playerFleet.cargo.getCommodityQuantity(CARGO_TYPE) >= CARGO_WEIGHT

    fun complete() {
        stage = Stage.Completed
        state.completeDateInMillis = game.sector.clock.timestamp

        game.sector.playerFleet.cargo.removeCommodity(CARGO_TYPE, CARGO_WEIGHT.toFloat())
        game.sector.playerFleet.cargo.credits.add(REWARD_CREDITS)
    }

    /**
     * 55 years after quest was completed.
     */
    fun shouldShowStage3Dialog(): Boolean {
        // If complete date is set, use that. If not (happens if quest was completed prior to the field being added)
        // then use startDate. If neither exist, use "0" just to avoid null, since the stage needs to be Completed anyway
        // so it won't trigger before then.
        val timestampQuestCompletedInSeconds = (state.completeDateInMillis ?: state.startDate ?: 0)
        return (stage == Stage.Completed
                && game.sector.clock.getElapsedDaysSince(timestampQuestCompletedInSeconds) > (365 * 55))
    }

    fun completeSecret() {
        stage = Stage.CompletedSecret
        state.secretCompleteDateInMillis = game.sector.clock.timestamp
    }

    fun restartQuest() {
        game.logger.i { "Restarting Telos quest." }

        state.map.clear()
        stage = Stage.NotStarted
    }

    private fun isValidPlanetForDestination(planet: PlanetAPI): Boolean =
        planet.market?.factionId?.toLowerCase() !in listOf("luddic_church", "luddic_path")
                && !planet.isGasGiant
                && !planet.isStar

    fun createPulsarSystem(): Boolean {
        if (game.sector.getStarSystem(game.text["nirv_starSystem_name"]) != null) {
            return false
        }

        // Create the system
        val newSystem = game.sector.createStarSystem(game.text["nirv_starSystem_name"])

        // Create the neutron star
        // Adapted from StarSystemGenerator.addStars
        val spec =
            game.settings.getSpec(StarGenDataSpec::class.java, StarTypes.NEUTRON_STAR, false) as StarGenDataSpec
        val radius = (spec.minRadius..spec.maxRadius).random()
        var corona: Float =
            radius * (spec.coronaMult + spec.coronaVar * (Random.nextFloat() - 0.5f))
        if (corona < spec.coronaMin) corona = spec.coronaMin

        val star = newSystem.initStar(
            "${MOD_ID}_telos_star",
            StarTypes.NEUTRON_STAR,
            radius,
            corona,
            spec.solarWind,
            (spec.minFlare..spec.maxFlare).random(),
            spec.crLossMult
        )

        newSystem.lightColor = Misc.interpolateColor(spec.lightColorMin, spec.lightColorMax, Random.nextFloat())
        newSystem.star = star

        // Adapted from StarSystemGenerator.setPulsarIfNeutron
        val coronaPlugin = Misc.getCoronaFor(star)

        if (coronaPlugin != null) {
            newSystem.removeEntity(coronaPlugin.entity)
        }

        newSystem.addCorona(star, 300f, 3f, 0f, 3f) // cr loss

        val eventHorizon: SectorEntityToken = newSystem.addTerrain(
            Terrain.PULSAR_BEAM,
            StarCoronaTerrainPlugin.CoronaParams(
                star.radius + corona, (star.radius + corona) / 2f,
                star,
                spec.solarWind,
                (spec.minFlare..spec.maxFlare).random(),
                spec.crLossMult
            )
        )
        eventHorizon.setCircularOrbit(star, 0f, 0f, 100f)

        // Add planet
        addPlanetToSystem(newSystem)

        // Find a constellation to add it to
        val constellations =
            game.sector.getConstellations()
                // Prefer an un-visited constellation
                .prefer { it.systems.all { system -> system.lastPlayerVisitTimestamp == 0L } }
                .sortedByDescending { it.location.distanceFromPlayerInHyperspace }

        // Need to put in hyperspace and generate jump points so we have a maxRadiusInHyperspace
        newSystem.autogenerateHyperspaceJumpPoints(true, true)

        for (constellation in constellations) {
            val xCoords = constellation.systems.mapNotNull { it.location.x }
            val yCoords = constellation.systems.mapNotNull { it.location.y }

            // Try 5000 points
            for (i in 0..5000) {
                val point =
                    Vector2f(
                        (xCoords.minOrNull()!!..xCoords.maxOrNull()!!).random(),
                        (yCoords.minOrNull()!!..yCoords.maxOrNull()!!).random()
                    )


                val doesPointIntersectWithAnySystems = constellation.systems.any { system ->
                    doCirclesIntersect(
                        centerA = point,
                        radiusA = newSystem.maxRadiusInHyperspace,
                        centerB = system.location,
                        radiusB = system.maxRadiusInHyperspace
                    )
                }

                if (!doesPointIntersectWithAnySystems) {
                    newSystem.constellation = constellation
                    constellation.systems.add(newSystem)
                    newSystem.location.set(point)
                    newSystem.autogenerateHyperspaceJumpPoints(true, true)
                    game.logger.i { "System ${newSystem.baseName} added to the ${constellation.name} constellation." }
                    return true
                }
            }
        }

        game.logger.i { "Failed to find anywhere to add a new system!" }
        return false
    }

    fun addPlanetToSystem(system: StarSystemAPI) {
        val planetType = "barren"
        val spec = game.settings.getSpec(PlanetGenDataSpec::class.java, planetType, false) as PlanetGenDataSpec
        val planet = system.addPlanet(
            "${MOD_ID}_telos_planet",
            system.star,
            game.text["nirv_starSystem_planetName"],
            planetType,
            0f,
            (spec.minRadius..spec.maxRadius).random(),
            3000f,
            50f
        )
        Misc.initConditionMarket(planet)
        planet.market.addCondition(Conditions.IRRADIATED)
    }

    abstract class Stage(progress: Progress) : AutoQuestFacilitator.Stage(progress) {
        object NotStarted : Stage(Progress.NotStarted)
        object GoToPlanet : Stage(Progress.InProgress)
        object Completed : Stage(Progress.Completed)
        object CompletedSecret : Stage(Progress.Completed)
    }
}