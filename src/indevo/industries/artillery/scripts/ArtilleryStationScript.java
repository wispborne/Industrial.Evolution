package indevo.industries.artillery.scripts;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent;
import com.fs.starfarer.api.impl.campaign.fleets.DefaultFleetInflater;
import com.fs.starfarer.api.impl.campaign.fleets.DefaultFleetInflaterParams;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.*;
import com.fs.starfarer.api.impl.campaign.procgen.themes.MiscellaneousThemeGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.RemnantOfficerGeneratorPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin;
import com.fs.starfarer.api.loading.IndustrySpecAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import indevo.ids.Ids;
import indevo.industries.artillery.conditions.ArtilleryStationCondition;
import indevo.industries.artillery.entities.ArtilleryStationEntityPlugin;
import indevo.industries.artillery.industry.ArtilleryStation;
import indevo.utils.ModPlugin;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static indevo.industries.artillery.entities.WatchtowerEntityPlugin.MEM_SENSOR_LOCK_ACTIVE;

/**
 * PERMANENT
 */

public class ArtilleryStationScript implements EveryFrameScript, FleetEventListener {

    public ArtilleryStationScript(MarketAPI market) {
        this.primaryEntity = market.getPrimaryEntity();
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    public static final String TYPE_KEY = "$IndEvo_ArtilleryType";
    public static final String ARTILLERY_KEY = "$IndEvo_ArtilleryStation";
    public static final String SCRIPT_KEY = "$IndEvo_ArtilleryStationScript";

    protected CampaignFleetAPI stationFleet = null;
    protected SectorEntityToken stationEntity = null;
    protected SectorEntityToken brokenStationEntity = null;

    public boolean isDestroyed = false;
    private String orbitMatched = null;
    public boolean isDiscoverable = true;

    protected SectorEntityToken primaryEntity;

    public static CampaignFleetAPI getStationFleet(SectorEntityToken entity) {
        return getScript(entity).getStationFleet();
    }

    public static ArtilleryStationScript getScript(SectorEntityToken entity){
        ArtilleryStationScript script;

        if (entity instanceof PlanetAPI) {
            script = (ArtilleryStationScript) entity.getMemoryWithoutUpdate().get(SCRIPT_KEY);
        } else {
            script = (ArtilleryStationScript) entity.getOrbitFocus().getMemoryWithoutUpdate().get(SCRIPT_KEY);
        }

        return script;
    }

    public static SectorEntityToken getArtilleryStation(SectorEntityToken planet) {
        SectorEntityToken t = null;
        ArtilleryStationScript script = (ArtilleryStationScript) planet.getMemoryWithoutUpdate().get(SCRIPT_KEY);
        if (script != null) t = script.getStationEntity();

        return t;
    }

    public void setDestroyed(boolean destroyed) {
        isDestroyed = destroyed;
    }

    public CampaignFleetAPI getStationFleet() {
        return stationFleet;
    }

    public SectorEntityToken getStationEntity() {
        return stationEntity;
    }

    @Override
    public void advance(float amount) {
        if (Global.getSector().getEconomy().isSimMode() || !primaryEntity.isInCurrentLocation()) return;

        //market = primaryEntity.getMarket();
        if (isDestroyed) destroyedActions();
        else aliveActions();
    }

    public void destroyedActions() {
        MarketAPI market = primaryEntity.getMarket();
        updateWatchtowers(false);

        if (brokenStationEntity != null && orbitMatched == null && market.getPrimaryEntity() instanceof PlanetAPI) { //we only match planets
            SectorEntityToken station = ArtilleryStationEntityPlugin.getOrbitalStationAtMarket(market);

            if (station != null && !station.getId().equals(orbitMatched)) {
                matchOrbitalstationOrbit(brokenStationEntity, station);
                ModPlugin.log("destroyed matching orbit");
            }
        }

        if (marketHasArtilleryIndustry()) {
            ArtilleryStationCondition.setDestroyed(false, market);
            removeBrokenStationEntityIfNeeded();
        } else {
            ArtilleryStationCondition.setDestroyed(true, market);
            spawnBrokenStationEntityIfNeeded();

            ArtilleryStationEntityPlugin plugin = getArtilleryPlugin();
            if (plugin != null) plugin.setDisrupted(true);

            matchCommanderToAICore(null);
        }
    }

    public void updateFaction() {
        String faction = null;
        String currentFaction = stationEntity.getFaction().getId();

        for (MarketAPI m : Misc.getMarketsInLocation(primaryEntity.getContainingLocation())) {
            if (m.isPlayerOwned() || m.getFaction().isPlayerFaction() || m.hasTag("epta_tradebase_market")) continue;

            faction = m.getFactionId();
            break;
        }

        if (faction == null) {
            for (CampaignFleetAPI fleet : primaryEntity.getContainingLocation().getFleets()) {
                if (fleet.isStationMode() && !fleet.getFaction().isPlayerFaction() && !fleet.getTags().contains(Ids.TAG_ARTILLERY_STATION_FLEET)) {
                    faction = fleet.getFaction().getId();
                    break;
                }
            }
        }

        if (faction != null && !faction.equals(currentFaction)) {
            updateFaction(faction);

        } else if (faction == null
                && !primaryEntity.isInCurrentLocation()) {

            updateFaction(Ids.DERELICT_FACTION_ID); //revert to derelict if player is out of system and the path/pirate base is gone
            //we do not revert if the faction is of a station fleet
        }

        String factionID = stationEntity.getFaction().getId();
        updateWatchtowers(factionID.equals(Ids.DERELICT_FACTION_ID) || factionID.equals(Factions.REMNANTS));
    }

    private void updateWatchtowers(boolean active) {
        for (SectorEntityToken t : primaryEntity.getContainingLocation().getEntitiesWithTag("IndEvo_watchtower"))
            t.getMemoryWithoutUpdate().set(MEM_SENSOR_LOCK_ACTIVE, active);
    }

    public void updateFaction(String id) {
        stationEntity.setFaction(id);
        stationFleet.setFaction(id, false);

        for (SectorEntityToken t : primaryEntity.getContainingLocation().getEntitiesWithTag("IndEvo_watchtower")) {
            if (!t.getFaction().isPlayerFaction()) t.setFaction(id);
        }
    }

    public void aliveActions() {
        if (stationEntity == null) {
            spawnStation();
        }

        MarketAPI market = primaryEntity.getMarket();
        if (!market.isPlanetConditionMarketOnly()) {
            setDestroyedWithDestroyedActions();
            return;
        }

        isDiscoverable = stationEntity.isDiscoverable();
        updateFaction();

        saveDebrisFieldStatus();

        getArtilleryPlugin().setDisrupted(false);
        ArtilleryStationCondition.setDestroyed(false, market);

        if (stationFleet != null) {
            stationFleet.setAI(null);

            applyCRToStation();

            if (stationFleet.getOrbit() == null && stationEntity != null) {
                stationFleet.setCircularOrbit(stationEntity, 0, 0, 100);
            }
        }
    }

    public List<String> getDebrisFieldList() {
        MemoryAPI mem = primaryEntity.getMemoryWithoutUpdate();

        if (mem.contains("$IndEvo_debrisFieldList")) return (List<String>) mem.get("$IndEvo_debrisFieldList");
        else {
            List<String> l = new ArrayList<String>();
            mem.set("$IndEvo_debrisFieldList", l);
            return l;
        }
    }

    public void addToDebrisFieldList(CampaignTerrainAPI t) {
        List<String> l = getDebrisFieldList();
        String id = t.getId();

        if (!l.contains(id)) {
            ModPlugin.log("adding debris field to memory: " + id);

            DebrisFieldTerrainPlugin.DebrisFieldParams params = ((DebrisFieldTerrainPlugin) t.getPlugin()).getParams();
            ModPlugin.log(params.lastsDays + " " + params.name + " " + params.defFaction + " " + params.relatedEntity);

            l.add(id);
            primaryEntity.getMemoryWithoutUpdate().set("$IndEvo_debrisFieldList", l);
        }
    }

    public void saveDebrisFieldStatus() {
        for (CampaignTerrainAPI t : getCombatDebrisFields(primaryEntity.getContainingLocation())) {
            addToDebrisFieldList(t);
        }
    }

    public boolean marketHasArtilleryIndustry() {
        boolean hasArty = false;

        MarketAPI market = primaryEntity.getMarket();

        if (!market.isPlanetConditionMarketOnly()) {
            for (Industry i : market.getIndustries()) {
                if (i instanceof ArtilleryStation) {
                    hasArty = true;
                    break;
                }
            }

            for (ConstructionQueue.ConstructionQueueItem i : market.getConstructionQueue().getItems()) {
                if (i.id.toLowerCase().contains("artillery")) {
                    hasArty = true;
                    break;
                }
            }
        }

        return hasArty;
    }

    public void removeBrokenStationEntityIfNeeded() {
        if (brokenStationEntity != null) {
            Misc.fadeAndExpire(brokenStationEntity, 0f);
            brokenStationEntity = null;
            ModPlugin.log("destroyed removing broken");
        }
    }

    public void spawnBrokenStationEntityIfNeeded() {
        if (brokenStationEntity == null) {
            MarketAPI market = primaryEntity.getMarket();
            SectorEntityToken brokenStation = market.getContainingLocation().addCustomEntity(Misc.genUID(), null, "IndEvo_DestroyedArtilleryStation", Ids.DERELICT_FACTION_ID, null);

            if (stationEntity != null) brokenStation.setOrbit(stationEntity.getOrbit());
            else {
                SectorEntityToken station = ArtilleryStationEntityPlugin.getOrbitalStationAtMarket(market);
                if (!matchOrbitalstationOrbit(brokenStation, station))
                    addRandomOrbit(brokenStation); //matches station orbit, if it can't, adds random orbit
            }

            brokenStationEntity = brokenStation;

            ModPlugin.log("destroyed adding broken");
        }

        removeStationEntityAndFleetIfNeeded();
    }

    public void addRandomOrbit(SectorEntityToken entity) {
        float angle = (float) Math.random() * 360f;
        float radius = primaryEntity.getRadius() + 150f;
        float period = radius / 10f;

        entity.setCircularOrbitWithSpin(primaryEntity,
                angle,
                radius,
                period,
                5f,
                5f);
    }

    public boolean matchOrbitalstationOrbit(SectorEntityToken entity, SectorEntityToken station) {
        if (station == null) return false;

        float angle = station.getCircularOrbitAngle() - 180;
        float radius = station.getCircularOrbitRadius();
        float period = station.getCircularOrbitPeriod();

        entity.setCircularOrbitWithSpin(primaryEntity,
                angle,
                radius,
                period,
                5f,
                5f);

        orbitMatched = station.getId();

        return true;
    }

    public void setType() {
        MarketAPI market = primaryEntity.getMarket();
        WeightedRandomPicker<String> picker = new WeightedRandomPicker<>();
        picker.addAll(Arrays.asList("mortar", "railgun", "missile"));
        market.getMemoryWithoutUpdate().set(TYPE_KEY, picker.pick());
    }

    protected void applyCRToStation() {
        if (stationFleet != null) {
            float cr = 1f;

            for (FleetMemberAPI member : stationFleet.getFleetData().getMembersListCopy()) {
                member.getRepairTracker().setCR(cr);
            }
            FleetInflater inflater = stationFleet.getInflater();
            if (inflater != null) {
                if (stationFleet.isInflated()) {
                    stationFleet.deflate();
                }

                MarketAPI market = primaryEntity.getMarket();
                inflater.setQuality(Misc.getShipQuality(market));
                if (inflater instanceof DefaultFleetInflater) {
                    DefaultFleetInflater dfi = (DefaultFleetInflater) inflater;
                    ((DefaultFleetInflaterParams) dfi.getParams()).allWeapons = true;
                }
            }
        }
    }

    protected void removeStationEntityAndFleetIfNeeded() {
        if (stationEntity != null) {

            MarketAPI market = primaryEntity.getMarket();
            ModPlugin.log("removing artillery station at " + market.getName());

            MemoryAPI memory = stationEntity.getMemoryWithoutUpdate();
            memory.unset(MemFlags.STATION_FLEET);
            memory.unset(MemFlags.STATION_BASE_FLEET);
            memory.unset("$hasDefenders");

            MemoryAPI planetMemory = primaryEntity.getMemoryWithoutUpdate();
            planetMemory.unset("$hasDefenders");

            ArtilleryStationEntityPlugin plugin = ((ArtilleryStationEntityPlugin) stationEntity.getCustomPlugin());
            if (plugin != null) plugin.getOrInitScript().preRemoveActions();

            stationEntity.getContainingLocation().removeEntity(stationFleet);

            if (stationEntity.getContainingLocation() != null) {
                stationEntity.getContainingLocation().removeEntity(stationEntity);
                market.getConnectedEntities().remove(stationEntity);

                // commented out so that MarketCMD doesn't NPE if you destroy a market through bombardment of a station
                //stationEntity.setMarket(null);

            } else if (stationEntity.hasTag(Tags.USE_STATION_VISUAL)) {
                ((CustomCampaignEntityAPI) stationEntity).setFleetForVisual(null);
                float origRadius = ((CustomCampaignEntityAPI) stationEntity).getCustomEntitySpec().getDefaultRadius();
                ((CustomCampaignEntityAPI) stationEntity).setRadius(origRadius);
            }

            if (stationFleet != null) {
                stationFleet.getMemoryWithoutUpdate().unset(MemFlags.STATION_MARKET);
                stationFleet.removeEventListener(this);
            }

            stationEntity = null;
            stationFleet = null;
        }
    }

    protected void spawnStation() {

        FleetParamsV3 fParams = new FleetParamsV3(null, null,
                Ids.DERELICT_FACTION_ID,
                1f,
                FleetTypes.PATROL_SMALL,
                0,
                0, 0, 0, 0, 0, 0);
        fParams.allWeapons = true;

        removeStationEntityAndFleetIfNeeded();

        stationFleet = FleetFactoryV3.createFleet(fParams);
        stationFleet.setNoFactionInName(true);

        stationFleet.setStationMode(true);
        stationFleet.clearAbilities();
        stationFleet.addAbility(Abilities.TRANSPONDER);
        stationFleet.getAbility(Abilities.TRANSPONDER).activate();
        stationFleet.getDetectedRangeMod().modifyFlat("gen", 10000f);

        stationFleet.setAI(null);
        stationFleet.addEventListener(this);

        stationFleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
        stationFleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);

        ensureStationEntityIsSetOrCreated();

        if (stationEntity instanceof CustomCampaignEntityAPI) {
            if (stationEntity.hasTag(Tags.USE_STATION_VISUAL)) {
                ((CustomCampaignEntityAPI) stationEntity).setFleetForVisual(stationFleet);
                stationEntity.setCustomDescriptionId(getSpec().getId());
            }
        }

        MarketAPI market = primaryEntity.getMarket();
        stationFleet.setCircularOrbit(stationEntity, 0, 0, 100);
        stationFleet.getMemoryWithoutUpdate().set(MemFlags.STATION_MARKET, market);
        stationFleet.setHidden(true);

        matchStationAndCommanderToCurrentIndustry();

        stationFleet.setName(market.getName() + " " + Misc.ucFirst(getType()) + " Station");
        stationEntity.setName(market.getName() + " " + Misc.ucFirst(getType()) + " Station");

        stationEntity.setDiscoverable(isDiscoverable);
        if (isDiscoverable) if (Misc.getMarketsInLocation(primaryEntity.getContainingLocation()).isEmpty())
            MiscellaneousThemeGenerator.makeDiscoverable(stationEntity, 300f, 2000f);

        //spawn defence fleet on player system enter
        //attach listener
        //station visual is arty visual even if defender fleet changes
        //despawn defender fleet when player leaves system

        MemoryAPI planetMemory = primaryEntity.getMemoryWithoutUpdate();
        planetMemory.set("$hasDefenders", true);
        planetMemory.set(ARTILLERY_KEY, true);

        MemoryAPI stationMemory = stationEntity.getMemoryWithoutUpdate();
        stationMemory.set("$hasDefenders", true);
        stationMemory.set(ARTILLERY_KEY, true);
    }

    protected void ensureStationEntityIsSetOrCreated() {
        if (stationEntity == null) {
            MarketAPI market = primaryEntity.getMarket();
            ModPlugin.log("spawning artillery station at " + market.getName());

            stationEntity = ArtilleryStationEntityPlugin.placeAtMarket(market, getType(), true);
        }
    }

    public String getType() {
        MarketAPI market = primaryEntity.getMarket();
        MemoryAPI mem = market.getMemoryWithoutUpdate();
        if (!mem.contains(TYPE_KEY)) setType();

        return mem.getString(TYPE_KEY);
    }

    public IndustrySpecAPI getSpec() {
        return Global.getSettings().getIndustrySpec("IndEvo_Artillery_" + getType());
    }

    protected void matchStationAndCommanderToCurrentIndustry() {
        stationFleet.getFleetData().clear();

        String fleetName = null;
        String variantId = null;
        float radius = 60f;

        try {
            JSONObject json = new JSONObject(getSpec().getData());
            variantId = json.getString("variant");
            radius = (float) json.getDouble("radius");
            fleetName = json.getString("fleetName");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        if (stationEntity != null) {
            fleetName = stationEntity.getName();
        }

        stationFleet.setName(Misc.ucFirst(getType()) + " defence platform");

        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variantId);
        String name = fleetName;
        member.setShipName(name);

        stationFleet.getFleetData().addFleetMember(member);
        applyCRToStation();

        if (stationEntity instanceof CustomCampaignEntityAPI) {
            ((CustomCampaignEntityAPI) stationEntity).setRadius(radius);
        } else if (stationEntity.hasTag(Tags.USE_STATION_VISUAL)) {
            ((CustomCampaignEntityAPI) stationEntity).setRadius(radius);
        }

        boolean skeletonMode = isDestroyed;

        if (skeletonMode) {
            stationEntity.getMemoryWithoutUpdate().unset(MemFlags.STATION_FLEET);
            stationEntity.getMemoryWithoutUpdate().set(MemFlags.STATION_BASE_FLEET, stationFleet);
            stationEntity.getContainingLocation().removeEntity(stationFleet);

            for (int i = 1; i < member.getStatus().getNumStatuses(); i++) {
                ShipVariantAPI variant = member.getVariant();
                if (i > 0) {
                    String slotId = member.getVariant().getModuleSlots().get(i - 1);
                    variant = variant.getModuleVariant(slotId);
                } else {
                    continue;
                }

                if (!variant.hasHullMod(HullMods.VASTBULK)) {
                    member.getStatus().setDetached(i, true);
                    member.getStatus().setPermaDetached(i, true);
                    member.getStatus().setHullFraction(i, 0f);
                }
            }

        } else {
            stationEntity.getMemoryWithoutUpdate().unset(MemFlags.STATION_BASE_FLEET);
            stationEntity.getMemoryWithoutUpdate().set(MemFlags.STATION_FLEET, stationFleet);
            stationEntity.getContainingLocation().removeEntity(stationFleet);
            stationFleet.setExpired(false);
            stationEntity.getContainingLocation().addEntity(stationFleet);
        }
    }

    protected int getHumanCommanderLevel() {
        return Global.getSettings().getInt("tier1StationOfficerLevel");
    }

    protected void matchCommanderToAICore(String aiCore) {
        if (stationFleet == null) return;

        PersonAPI commander = null;
        if (Commodities.ALPHA_CORE.equals(aiCore)) {

            AICoreOfficerPlugin plugin = Misc.getAICoreOfficerPlugin(Commodities.ALPHA_CORE);
            commander = plugin.createPerson(Commodities.ALPHA_CORE, Factions.REMNANTS, null);
            if (stationFleet.getFlagship() != null) {
                RemnantOfficerGeneratorPlugin.integrateAndAdaptCoreForAIFleet(stationFleet.getFlagship());
            }
        } else {
            if (stationFleet.getFlagship() != null) {
                int level = getHumanCommanderLevel();
                PersonAPI current = stationFleet.getFlagship().getCaptain();
                if (level > 0) {
                    if (current.isAICore() || current.getStats().getLevel() != level) {
                        MarketAPI market = primaryEntity.getMarket();
                        commander = OfficerManagerEvent.createOfficer(
                                Global.getSector().getFaction(market.getFactionId()), level, true);
                    }
                } else {
                    if (stationFleet.getFlagship() == null || stationFleet.getFlagship().getCaptain() == null ||
                            !stationFleet.getFlagship().getCaptain().isDefault()) {
                        commander = Global.getFactory().createPerson();
                    }
                }
            }

        }

        if (commander != null) {
            if (stationFleet.getFlagship() != null) {
                stationFleet.getFlagship().setCaptain(commander);
                stationFleet.getFlagship().setFlagship(false);
            }
        }
    }

    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
    }

    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, CampaignEventListener.FleetDespawnReason reason, Object param) {
        if (fleet != stationFleet && fleet != null) return; // shouldn't happen...

        // bug where somehow a station fleet can become empty as a result of combat
        // then its despawn() gets called every frame
        if (stationFleet.getMembersWithFightersCopy().isEmpty()) {
            matchStationAndCommanderToCurrentIndustry();
        }

        setDestroyedWithDestroyedActions();

        ModPlugin.log(primaryEntity.getName() + " Artillery station destroyed");

        //relocates the debris field that was spawned last, which gotta be the one from the station because there is one frame between station death and reportFleetDespawnedToListener, during which it gets spawned
        //this is a crime against humanity
        saveDebrisFieldStatus();

        LocationAPI loc = primaryEntity.getContainingLocation();
        List<String> l = getDebrisFieldList();
        if (!getDebrisFieldList().isEmpty() && brokenStationEntity != null) {
            SectorEntityToken t = loc.getEntityById(l.get(l.size() - 1));

            if (t == null) return;
            t.setLocation(brokenStationEntity.getLocation().x, brokenStationEntity.getLocation().y);
        }
    }

    private void setDestroyedWithDestroyedActions() {
        primaryEntity.getMemoryWithoutUpdate().unset("$hasDefenders");
        primaryEntity.getMemoryWithoutUpdate().set("$defenderFleetDefeated", true);

        stationFleet.setAbortDespawn(true);
        isDestroyed = true;
        spawnBrokenStationEntityIfNeeded();
    }

    public static List<CampaignTerrainAPI> getCombatDebrisFields(LocationAPI loc) {
        List<CampaignTerrainAPI> results = new ArrayList<>();

        for (CampaignTerrainAPI terrain : loc.getTerrainCopy()) {
            if (terrain.getType().equals("debris_field")) {
                DebrisFieldTerrainPlugin plugin = (DebrisFieldTerrainPlugin) terrain.getPlugin();

                if (!plugin.isScavenged() && !plugin.isFadingOut()) {
                    DebrisFieldTerrainPlugin.DebrisFieldParams params = ((DebrisFieldTerrainPlugin) terrain.getPlugin()).getParams();

                    if (params.lastsDays < 60.0F && params.source == DebrisFieldTerrainPlugin.DebrisFieldSource.BATTLE) {
                        results.add(terrain);
                    }
                }
            }
        }

        return results;
    }

    public ArtilleryStationEntityPlugin getArtilleryPlugin() {
        if (stationEntity != null) return (ArtilleryStationEntityPlugin) stationEntity.getCustomPlugin();

        MarketAPI market = primaryEntity.getMarket();

        for (SectorEntityToken t : market.getConnectedEntities()) {
            if (t.getCustomPlugin() instanceof ArtilleryStationEntityPlugin)
                return (ArtilleryStationEntityPlugin) t.getCustomPlugin();
        }

        return null;
    }

}
