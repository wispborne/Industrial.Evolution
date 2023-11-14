package indevo.exploration.minefields;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidSource;
import com.fs.starfarer.api.impl.campaign.terrain.BaseRingTerrain;
import com.fs.starfarer.api.impl.campaign.terrain.RingRenderer;
import com.fs.starfarer.api.impl.campaign.terrain.RingSystemTerrainPlugin;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import com.fs.starfarer.campaign.BaseLocation;
import indevo.utils.ModPlugin;
import indevo.utils.helper.Settings;
import org.apache.log4j.Logger;

import java.awt.*;
import java.util.List;
import java.util.*;

import static indevo.exploration.minefields.conditions.MineFieldCondition.PLANET_KEY;

public class MineBeltTerrainPlugin extends BaseRingTerrain implements AsteroidSource {

    public static float MIN_MINE_SIZE = 2f;
    public static float MAX_MINE_SIZE = 8f;

    public static final float CIVILIAN_EFFECT_MULT = Settings.MINEFIELD_CIVILIAN_SHIP_IMPACT_MULT;
    public static final float PHASE_EFFECT_MULT = Settings.MINEFIELD_PHASE_SHIP_IMPACT_MULT;
    public static final float MAX_FLEET_SIZE_BEFORE_MALUS = Settings.MINEFIELD_NOHITUNTILSUM;

    public static final float RECENT_JUMP_TIMEOUT_SECONDS = 2f;
    public static final String RECENT_JUMP_KEY = "$IndEvo_recentlyJumped";

    public static final String LOCATION_DISABLED_AREA_MEMORY = "$IndEvo_MinesDisabledLocMem";

    public static final Logger log = Global.getLogger(MineBeltTerrainPlugin.class);

    public static class MineBeltParams extends RingParams {
        public int numMines;

        public float minOrbitDays;
        public float maxOrbitDays;
        public float minSize;
        public float maxSize;

        public MineBeltParams(int numMines, float orbitRadius,
                              float width, float minOrbitDays, float maxOrbitDays,
                              float minSize, float maxSize, String name) {
            super(width, orbitRadius, null, name);
            this.numMines = numMines;
            this.minOrbitDays = minOrbitDays;
            this.maxOrbitDays = maxOrbitDays;
            this.minSize = minSize;
            this.maxSize = maxSize;
        }
    }

    public static SectorEntityToken addMineBelt(SectorEntityToken focus,
                                                float orbitRadius, float width, float minOrbitDays,
                                                float maxOrbitDays, String optionalName) {

        CampaignTerrainAPI mineBelt = ((BaseLocation) focus.getContainingLocation()).addTerrain("IndEvo_mine_belt", new MineBeltTerrainPlugin.MineBeltParams(
                (int) Math.round(orbitRadius / 3f), //this specifies the mine amount
                orbitRadius,
                width,
                minOrbitDays,
                maxOrbitDays,
                MIN_MINE_SIZE,
                MAX_MINE_SIZE,
                optionalName));

        mineBelt.setCircularOrbit(focus, 0.0F, 0.0F, 100.0F);
        return mineBelt;
    }

    @Override
    protected Object readResolve() {
        super.readResolve();
        return this;
    }

    private transient RingRenderer rr;

    public void renderOnMap(float factor, float alphaMult) {
        if (params == null) return;
        if (rr == null) {
            rr = new RingRenderer("systemMap", "map_asteroid_belt");
        }
        Color color = Global.getSettings().getColor("asteroidBeltMapColor");
        float bandWidth = params.bandWidthInEngine;
        bandWidth = 300f;
        rr.render(entity.getLocation(),
                params.middleRadius - bandWidth * 0.5f,
                params.middleRadius + bandWidth * 0.5f,
                color,
                false, factor, alphaMult);
    }

    public void regenerateAsteroids() {
        createMines();
    }

    protected boolean needToCreateMines = true;

    protected void createMines() {
        if (params == null || !Settings.ENABLE_MINEFIELDS) return;

        Random rand = new Random(Global.getSector().getClock().getTimestamp() + entity.getId().hashCode());

        List<Pair<Float, Float>> usedAngles = new ArrayList<>();

        LocationAPI location = entity.getContainingLocation();
        for (int i = 0; i < params.numMines; i++) {
            //float size = 8f + (float) Math.random() * 25f;
            float size = params.minSize + rand.nextFloat() * (params.maxSize - params.minSize);
            SectorEntityToken mine = addMine(location, size);
            if (this.entity.getMemoryWithoutUpdate().contains(PLANET_KEY)) {
                mine.getMemoryWithoutUpdate().set(PLANET_KEY, entity.getMemoryWithoutUpdate().get(PLANET_KEY));
            }

            mine.setFacing(rand.nextFloat() * 360f);
            float currRadius = params.middleRadius - params.bandWidthInEngine / 2f + rand.nextFloat() * params.bandWidthInEngine;

            float angle = rand.nextFloat() * 360f;

            boolean hadToUpdateOrbit = true;
            int limiter = 1;

            while (hadToUpdateOrbit && limiter <= 30) {
                hadToUpdateOrbit = false;

                for (Pair<Float, Float> usedRange : usedAngles) {
                    if (isBetween(angle, usedRange.one, usedRange.two)) {
                        angle = rand.nextFloat() * 360f;

                        hadToUpdateOrbit = true;
                        limiter++;
                        break;
                    }
                }
            }

            usedAngles.add(new Pair<>(angle += 10f, angle -= 10f));

            float orbitDays = params.minOrbitDays + rand.nextFloat() * (params.maxOrbitDays - params.minOrbitDays);
            mine.setCircularOrbit(this.entity, angle, currRadius, orbitDays);
            Misc.setAsteroidSource(mine, this);
        }
        needToCreateMines = false;
    }

    private static boolean isBetween(float check, float lower, float higher) {
        return (check >= lower && check <= higher);
    }

    public static SectorEntityToken addMine(LocationAPI loc, float size) {
        return loc.addCustomEntity(Misc.genUID(), "Mine", "IndEvo_mine", null, size, 0f, 0f);
    }

    public void advance(float amount) {
        if (needToCreateMines) {
            createMines();
        }

        for (DisabledArea area : new ArrayList<>(getDisabledAreas())) {
            area.advance(amount);
        }

        super.advance(amount);
    }

    public MineBeltParams params;

    public void init(String terrainId, SectorEntityToken entity, Object param) {
        super.init(terrainId, entity, param);
        if (param instanceof MineBeltParams) {
            params = (MineBeltParams) param;
            name = params.name;
            if (name == null) {
                name = "Mine Belt";
            }
        }
    }

    public void render(CampaignEngineLayers layer, ViewportAPI viewport) {
        super.render(layer, viewport);
    }

    @Override
    public void applyEffect(SectorEntityToken entity, float days) {
        if (!Settings.ENABLE_MINEFIELDS) return;

        if (entity instanceof CampaignFleetAPI) {
            CampaignFleetAPI fleet = (CampaignFleetAPI) entity;
            boolean isSlow = Misc.isSlowMoving(fleet);

            if (isSlow) {
                fleet.getStats().addTemporaryModMult(0.1f, getModId() + "_2",
                        "Hiding inside " + getNameForTooltip().toLowerCase(), RingSystemTerrainPlugin.getVisibilityMult(fleet),
                        fleet.getStats().getDetectedRangeMod());
            }

            if (!isFriend(fleet) && fleet.getBattle() == null && !fleet.getMemoryWithoutUpdate().contains(RECENT_JUMP_KEY)) {

                for (DisabledArea area : getDisabledAreas()) if (area.contains(fleet)) return;

                String key = "$mineImpactTimeout";
                String sKey = "$skippedImpacts";
                String recentKey = "$recentImpact";

                float speed = fleet.getCurrBurnLevel(); //slow is <5, fast is 10+
                float probPerSkip = 0.3f;
                float maxSkipsToTrack = 7;
                float durPerSkip = 0.1f;

                MemoryAPI mem = fleet.getMemoryWithoutUpdate();

                if (!mem.contains(key) && !mem.contains(recentKey)) {
                    float expire = mem.getExpire(sKey);
                    if (expire < 0) expire = 0;

                    float hitProb = expire / durPerSkip * probPerSkip;

                    //size must be a mult, or it will weight too much - a large fleet going slow should still have a lower chance
                    float speedMult = 1f + ((Math.max(speed, 5f) - 5f) * 0.5f); //= x5 bei 15
                    float speedFlatPow = (float) Math.pow(speed, 2) * 0.003f;
                    float fleetCompBaseHitChance = getBaseFleetHitChance(fleet);
                    float chance = ((hitProb + Math.max(fleetCompBaseHitChance - MAX_FLEET_SIZE_BEFORE_MALUS, 0f))) + speedFlatPow;
                    if (speed >= 10f) chance += speedFlatPow;

                    boolean isSlowAndSmall = (Misc.isSlowMoving(fleet) && fleetCompBaseHitChance < MineBeltTerrainPlugin.MAX_FLEET_SIZE_BEFORE_MALUS);

                    if (false)
                        log.info("total " + chance + " | hitProb " + hitProb + " | fleetSize " + fleetCompBaseHitChance + " | burn " + speedFlatPow);

                    //nothing happens if fleet size <0.7 and slow moving
                    if (!isSlowAndSmall && (float) Math.random() < chance) {
                        fleet.addScript(new MineImpact(fleet));
                        mem.set(sKey, true, 0);

                        float timeoutNPC = (float) (5f + 2f * Math.random());
                        float timeoutPlayer = (float) (0.05f + 1f * Math.random());

                        mem.set(recentKey, true, fleet.isPlayerFleet() ? timeoutPlayer : timeoutNPC);
                    } else {
                        mem.set(sKey, true, Math.min(expire + durPerSkip, maxSkipsToTrack * durPerSkip));
                    }

                    float timeoutNPC = (float) (1f + 1f * Math.random());
                    float timeoutPlayer = (float) (0.05f + 0.1f * Math.random());

                    mem.set(key, true, fleet.isPlayerFleet() ? timeoutPlayer : timeoutNPC);
                    //mem.set(key, true, (float) (0.01f + 0.02f * Math.random()));
                }
            }
        }
    }

    private boolean isFriend(CampaignFleetAPI fleet) {
        boolean friend = false;

        MarketAPI m = getPrimary();

        if (m != null && !m.isPlanetConditionMarketOnly()) {
            friend = (fleet.isPlayerFleet() && m.isPlayerOwned())
                    || fleet.getFaction().getId().equals(m.getFactionId())
                    || fleet.getFaction().getRelationshipLevel(m.getFactionId()).isAtWorst(RepLevel.INHOSPITABLE);

            if (!m.isPlayerOwned() && fleet.isPlayerFleet() && !fleet.isTransponderOn()) friend = false;
        }

        if (fleet.getMemoryWithoutUpdate().contains(MemFlags.MEMORY_KEY_MISSION_IMPORTANT)) friend = true;

        return friend;
    }

    public MarketAPI getPrimary() {
        MarketAPI m = null;

        if (this.entity.getMemoryWithoutUpdate().contains(PLANET_KEY)) {
            m = this.entity.getMemoryWithoutUpdate().getEntity(PLANET_KEY).getMarket();
        } else if (this.entity.getOrbitFocus() != null) {
            m = this.entity.getOrbitFocus().getMarket();
        }

        return m;
    }

    public static float getBaseFleetHitChance(CampaignFleetAPI fleet) {
        Map<ShipAPI.HullSize, Float> hullSizeChanceMap = new HashMap<>();
        hullSizeChanceMap.put(ShipAPI.HullSize.FRIGATE, Settings.MINEFIELD_HITCHANCE_FRIGATE);
        hullSizeChanceMap.put(ShipAPI.HullSize.DESTROYER, Settings.MINEFIELD_HITCHANCE_DESTROYER);
        hullSizeChanceMap.put(ShipAPI.HullSize.CRUISER, Settings.MINEFIELD_HITCHANCE_CRUISER);
        hullSizeChanceMap.put(ShipAPI.HullSize.CAPITAL_SHIP, Settings.MINEFIELD_HITCHANCE_CAPITAL);

        float fleetCompBaseHitChance = 0f;
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            ShipAPI.HullSize hullSize = m.getHullSpec().getHullSize();

            if (!hullSizeChanceMap.containsKey(hullSize)) continue;

            boolean isCivilian = m.isCivilian();
            boolean isPhase = m.isPhaseShip();

            float effect = hullSizeChanceMap.get(hullSize);
            if (isCivilian) effect *= CIVILIAN_EFFECT_MULT;
            if (isPhase) effect *= PHASE_EFFECT_MULT;

            fleetCompBaseHitChance += effect;
        }

        return fleetCompBaseHitChance;
    }

    public boolean hasTooltip() {
        return true;
    }

    public String getNameForTooltip() {
        return "Mine Belt";
    }

    public String getNameAOrAn() {
        return "a";
    }

    public void createTooltip(TooltipMakerAPI tooltip, boolean expanded) {
        float pad = 10f;
        float small = 5f;
        Color gray = Misc.getGrayColor();
        Color highlight = Misc.getHighlightColor();
        Color fuel = Global.getSettings().getColor("progressBarFuelColor");
        Color bad = Misc.getNegativeHighlightColor();

        //tooltip.addTitle(params.name);
        tooltip.addTitle(getNameForTooltip());
        tooltip.addPara(Global.getSettings().getDescription(getTerrainId(), Description.Type.TERRAIN).getText1(), pad);

        float nextPad = pad;
        if (expanded) {
            tooltip.addSectionHeading("Travel", Alignment.MID, pad);
            nextPad = small;
        }

        tooltip.addPara("Extremely dangerous - If hostile to the controlling entity or running without a transponder, " +
                "there is a high chance of mine explosions " +
                "that knock the fleet off course and deal high damage to multiple ships.", nextPad);

        tooltip.addPara("Smaller and and slow-moving fleets have a better chance to make it through unscathed.", pad,
                highlight,
                "Smaller", "slow-moving"
        );

        float fleetCompBaseHitChance = Math.max(getBaseFleetHitChance(Global.getSector().getPlayerFleet()) - MAX_FLEET_SIZE_BEFORE_MALUS, 0f);
        Map<Float, Pair<String, Color>> chanceColourMap = new LinkedHashMap<>();
        chanceColourMap.put(0.7f, new Pair<String, Color>("high", Color.ORANGE));
        chanceColourMap.put(0.4f, new Pair<String, Color>("medium", Color.YELLOW));
        chanceColourMap.put(0.1f, new Pair<String, Color>("low", Color.GREEN));

        Pair<String, Color> p = new Pair<String, Color>("extreme", Color.RED);
        for (Map.Entry<Float, Pair<String, Color>> e : chanceColourMap.entrySet()) {
            if (fleetCompBaseHitChance < e.getKey()) p = e.getValue();
        }

        tooltip.addPara("Risk due to your fleet size: %s", pad,
                p.two,
                p.one
        );

        MarketAPI primary = getPrimary();
        if (primary != null)
            tooltip.addPara("This minefield is controlled though hidden facilities on %s.", pad, primary.getFaction().getColor(), primary.getPrimaryEntity().getName());

        String stop = Global.getSettings().getControlStringForEnumName("GO_SLOW");
        tooltip.addPara("Reduces the range at which stationary or slow-moving* fleets inside it can be detected by %s.", nextPad,
                highlight,
                "" + (int) ((1f - RingSystemTerrainPlugin.getVisibilityMult(Global.getSector().getPlayerFleet())) * 100) + "%"
        );
        tooltip.addPara("*Press and hold %s to stop; combine with holding the left mouse button down to move slowly. " +
                        "A slow-moving fleet moves at a burn level of half that of its slowest ship.", nextPad,
                Misc.getGrayColor(), highlight,
                stop
        );

    }

    public boolean isTooltipExpandable() {
        return true;
    }

    public float getTooltipWidth() {
        return 350f;
    }

    public String getEffectCategory() {
        return "asteroid_belt";
    }

    public boolean hasAIFlag(Object flag, CampaignFleetAPI fleet) {
        if (isFriend(fleet) || fleet.getMemoryWithoutUpdate().contains("$recentImpact"))
            return false;
        else return flag == TerrainAIFlags.DANGEROUS_UNLESS_GO_SLOW;
    }

    @Override
    public void reportAsteroidPersisted(SectorEntityToken asteroid) {
        if (Misc.getAsteroidSource(asteroid) == this) {
            params.numMines--;
        }
    }

    public static class DisabledArea {
        public float radius;
        public float duration;
        SectorEntityToken entity;

        public boolean isExpired = false;

        public DisabledArea(float radius, float duration, SectorEntityToken entity) {
            this.radius = radius;
            this.duration = duration;
            this.entity = entity;
        }

        public void init() {
            MemoryAPI mem = entity.getContainingLocation().getMemoryWithoutUpdate();
            if (!mem.contains(LOCATION_DISABLED_AREA_MEMORY))
                mem.set(LOCATION_DISABLED_AREA_MEMORY, new ArrayList<>(Collections.singletonList(this)));
            else ((List<DisabledArea>) mem.get(LOCATION_DISABLED_AREA_MEMORY)).add(this);
        }

        public boolean isExpired() {
            return isExpired;
        }

        public String getBeltId() {
            return entity.getOrbitFocus().getId();
        }

        public void advance(float amt) {
            duration -= amt;
            if (duration <= 0 && !isExpired) remove();
        }

        public void remove() {
            isExpired = true;
            entity.getContainingLocation().removeEntity(entity);

            MemoryAPI mem = entity.getContainingLocation().getMemoryWithoutUpdate();
            ((List<DisabledArea>) mem.get(LOCATION_DISABLED_AREA_MEMORY)).remove(this);
        }

        public boolean contains(SectorEntityToken t) {
            return Misc.getDistance(entity.getLocation(), t.getLocation()) <= radius;
        }
    }

    public List<DisabledArea> getDisabledAreas() {
        MemoryAPI mem = entity.getContainingLocation().getMemoryWithoutUpdate();
        List<DisabledArea> areaList = new ArrayList<>();

        if (mem.contains(LOCATION_DISABLED_AREA_MEMORY)) {
            List<DisabledArea> areas = ((List<DisabledArea>) mem.get(LOCATION_DISABLED_AREA_MEMORY));
            for (DisabledArea area : areas) {
                if (entity.getId().equals(area.getBeltId())) areaList.add(area);
            }
        }

        return areaList;
    }

    public void generateDisabledArea(SectorEntityToken fleet, float size, float dur) {
        //focus angle radius period
        ModPlugin.log("generating new mine disabled area");

        SectorEntityToken orbitFocus = fleet.getContainingLocation().addCustomEntity(Misc.genUID(), null, "SplinterFleet_OrbitFocus", Factions.NEUTRAL);
        orbitFocus.setLocation(fleet.getLocation().x, fleet.getLocation().y);

        orbitFocus.setCircularOrbit(entity, Misc.getAngleInDegrees(entity.getLocation(), orbitFocus.getLocation()), Misc.getDistance(entity, orbitFocus), entity.getOrbit().getOrbitalPeriod());

        DisabledArea area = new DisabledArea(size, dur, orbitFocus);
        area.init();
    }
}
