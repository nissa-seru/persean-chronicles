package wisp.perseanchronicles.telos.pt3_arrow

import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.CampaignPlugin
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.InteractionDialogPlugin
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.impl.campaign.missions.hub.ReqMode
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import org.json.JSONObject
import wisp.perseanchronicles.MOD_ID
import wisp.perseanchronicles.dangerousGames.pt1_dragons.DragonsQuest
import wisp.perseanchronicles.game
import wisp.perseanchronicles.telos.TelosCommon
import wisp.perseanchronicles.telos.pt1_deliveryToEarth.Telos1HubMission
import wisp.perseanchronicles.telos.pt3_arrow.nocturne.NocturneScript
import wisp.questgiver.InteractionDefinition
import wisp.questgiver.addPara
import wisp.questgiver.spriteName
import wisp.questgiver.v2.QGHubMission
import wisp.questgiver.v2.json.query
import wisp.questgiver.wispLib.*
import java.awt.Color

class Telos3HubMission : QGHubMission() {
    companion object {
        // Hardcode because it's being used in rules.csv.
        val MISSION_ID = "wisp_perseanchronicles_telosPt3"

        var part3Json: JSONObject =
            TelosCommon.readJson().query("/$MOD_ID/telos/part3_arrow") as JSONObject
            private set

        val tags = listOf(Tags.INTEL_STORY, Tags.INTEL_ACCEPTED)

        val state = State(PersistentMapData<String, Any?>(key = "telosPt3State").withDefault { null })
        val choices = Choices(PersistentMapData<String, Any?>(key = "telosPt3Choices").withDefault { null })
    }

    class State(val map: MutableMap<String, Any?>) {
        var startDateMillis: Long? by map
        var completeDateInMillis: Long? by map
        var ruinsPlanet: SectorEntityToken? by map
    }

    class Choices(val map: MutableMap<String, Any?>) {
    }

    init {
        missionId = MISSION_ID
    }

    override fun onGameLoad() {
        super.onGameLoad()

        // Reload json if devmode reload.
        if (isDevMode())
            part3Json = TelosCommon.readJson()
                .query("/$MOD_ID/telos/part3_arrow") as JSONObject
    }

    override fun updateTextReplacements(text: Text) {
        text.globalReplacementGetters["telosPt1Stg1DestPlanet"] = { Telos1HubMission.state.karengoPlanet?.name }
        text.globalReplacementGetters["telosPt1Stg1DestSystem"] = { Telos1HubMission.state.karengoSystem?.name }
        text.globalReplacementGetters["telosStarName"] =
            { Telos1HubMission.state.karengoPlanet?.starSystem?.star?.name }
        text.globalReplacementGetters["telosPt3RuinsSystem"] = { state.ruinsPlanet?.starSystem?.name }
        text.globalReplacementGetters["telosPt3RuinsPlanet"] = { state.ruinsPlanet?.name }
    }

    override fun create(createdAt: MarketAPI?, barEvent: Boolean): Boolean {
        // if already accepted by the player, abort
        if (!setGlobalReference("$$MISSION_ID")) {
            return false
        }

        // Ignore warning, there are two overrides and it's complaining about just one of them.
        @Suppress("ABSTRACT_SUPER_CALL_WARNING")
        super.create(createdAt, barEvent)
        setGenRandom(Telos1HubMission.state.seed ?: Misc.random)

        setStartingStage(Stage.GoToPlanet)
        setSuccessStage(Stage.Completed)

        name = part3Json.query("/strings/title")
        personOverride = DragonsQuest.karengo // Shows on intel, needed for rep reward or else crash.

        // todo change me
        setIconName(InteractionDefinition.Portrait(category = "intel", id = "red_planet").spriteName(game))

        val chasingFleet = "$${MISSION_ID}_chasingFleet"

        state.ruinsPlanet = SystemFinder()
            .requireSystemTags(mode = ReqMode.NOT_ANY, Tags.THEME_CORE)
            .requireSystemHasAtLeastNumJumpPoints(min = 1)
            .requirePlanetNotGasGiant()
            .requirePlanetNotStar()
            .preferEntityUndiscovered()
            .preferSystemNotPulsar()
            .preferPlanetWithRuins()
            .preferPlanetInDirectionOfOtherMissions()
            .pickPlanet()

        trigger {
            beginStageTrigger(Stage.EscapeSystem)
            triggerCreateFleet(
                FleetSize.MAXIMUM,
                FleetQuality.SMOD_1,
                Factions.HEGEMONY,
                FleetTypes.TASK_FORCE,
                state.ruinsPlanet
            )
            triggerMakeHostile()
            triggerAutoAdjustFleetStrengthExtreme()
            triggerPickLocationAroundEntity(state.ruinsPlanet?.starSystem?.jumpPoints?.random(), 1f)
            triggerSpawnFleetAtPickedLocation(chasingFleet, null)
            triggerFleetInterceptPlayerOnSight(false, Stage.EscapeSystem)

            triggerCustomAction {
                // Blind player
                game.sector.addScript(NocturneScript())
            }
        }

        return true
    }

    override fun acceptImpl(dialog: InteractionDialogAPI?, memoryMap: MutableMap<String, MemoryAPI>?) {
        super.acceptImpl(dialog, memoryMap)

        state.startDateMillis = game.sector.clock.timestamp
        setCurrentStage(Stage.GoToPlanet, null, null)
        makeImportant(
            state.ruinsPlanet,
            null,
            Stage.GoToPlanet,
        )
        makePrimaryObjective(state.ruinsPlanet)
    }

    override fun endSuccessImpl(dialog: InteractionDialogAPI?, memoryMap: MutableMap<String, MemoryAPI>?) {
        super.endSuccessImpl(dialog, memoryMap)

        state.completeDateInMillis = game.sector.clock.timestamp
    }

    override fun callAction(
        action: String?,
        ruleId: String?,
        dialog: InteractionDialogAPI?,
        params: MutableList<Misc.Token>?,
        memoryMap: MutableMap<String, MemoryAPI>?
    ): Boolean {
        // Trigger set in `create` (triggerFleetAddDefeatTrigger), fired off to rules.csv when fleet dies, listen for it here.
//        if (action == badFleetDefeatTrigger) {
//            setCurrentStage(Stage.LandOnPlanetFirst, null, null)
//            return true
//        }

        return super.callAction(action, ruleId, dialog, params, memoryMap)
    }

    override fun pickInteractionDialogPlugin(interactionTarget: SectorEntityToken): PluginPick<InteractionDialogPlugin>? {
        return if (interactionTarget.id == state.ruinsPlanet?.id) {
            when (currentStage) {
                Stage.GoToPlanet -> PluginPick(
                    Telos3LandingDialog().build(),
                    CampaignPlugin.PickPriority.MOD_SPECIFIC
                )
//                Stage.LandOnPlanetSecondPsicon,
//                Stage.LandOnPlanetSecondNoPsicon -> PluginPick(
//                    Telos2SecondLandingDialog().build(),
//                    CampaignPlugin.PickPriority.MOD_SPECIFIC
//                )
                else -> null
            }
        } else null
    }

    override fun endAbandonImpl() {
        super.endAbandonImpl()
        game.logger.i { "Abandoning ${this.name} quest." }

        state.map.clear()
        currentStage = null
    }

    /**
     * Bullet points on left side of intel.
     */
    override fun addNextStepText(info: TooltipMakerAPI, tc: Color?, pad: Float): Boolean {
        return when (currentStage) {
            Stage.GoToPlanet -> {
                info.addPara(padding = 3f, textColor = Misc.getGrayColor()) {
                    part3Json.query<String>("/stages/goToPlanet/intel/subtitle").qgFormat()
                }
                true
            }
            Stage.EscapeSystem -> {
                info.addPara(padding = 3f, textColor = Misc.getGrayColor()) {
                    part3Json.query<String>("/stages/landOnPlanetFirst/intel/subtitle").qgFormat()
                }
                true
            }
            Stage.Completed -> {
                info.addPara(padding = 3f, textColor = Misc.getGrayColor()) {
                    part3Json.query<String>("/stages/landOnPlanetSecondPsicon/intel/subtitle").qgFormat()
                }
                true
            }
            else -> false
        }
    }

    /**
     * Description on right side of intel.
     */
    override fun addDescriptionForCurrentStage(info: TooltipMakerAPI, width: Float, height: Float) {
        when (currentStage) {
            Stage.GoToPlanet -> {
                info.addPara { part3Json.query<String>("/stages/goToPlanet/intel/desc").qgFormat() }
            }
            Stage.EscapeSystem -> {
                info.addPara { part3Json.query<String>("/stages/landOnPlanetFirst/intel/desc").qgFormat() }
            }
            Stage.Completed -> {
                info.addPara { part3Json.query<String>("/stages/landOnPlanetSecondPsicon/intel/desc").qgFormat() }
            }
        }
    }

    enum class Stage {
        GoToPlanet,
        EscapeSystem,
        Completed,
    }
}