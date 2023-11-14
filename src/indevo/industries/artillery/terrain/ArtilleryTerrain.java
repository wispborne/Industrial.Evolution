package indevo.industries.artillery.terrain;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.terrain.BaseRingTerrain;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import indevo.industries.artillery.scripts.CampaignAttackScript;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;

import static indevo.industries.artillery.entities.ArtilleryStationEntityPlugin.*;

public class ArtilleryTerrain extends BaseRingTerrain {

    public void setRange(float range) {
        params.bandWidthInEngine = range;
    }

    public void remove() {
        Misc.fadeAndExpire(entity, 0f);
    }

    @Override
    public boolean canPlayerHoldStationIn() {
        return false;
    }

    public CampaignAttackScript getScript(SectorEntityToken entity){
        for (EveryFrameScript s : entity.getScripts()){
            if (s instanceof CampaignAttackScript) return (CampaignAttackScript) s;
        }

        return null;
    }

    @Override
    public String getTerrainName() {

        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        SectorEntityToken artillery = getRelatedEntity();
        CampaignAttackScript script = getScript(artillery);

        if (script == null) return "";

        boolean isSafe = script.isInSafeSpot(player);
        boolean isHostile = script.isHostileTo(player);

        if (artillery.isDiscoverable()) return "In Artillery Range";
        if (isHostile && isSafe) return "Artillery safe-spot";
        if (isHostile) return artillery.getName() + " - Hostile";
        return artillery.getName();
    }

    public boolean hasTooltip() {
        return true;
    }

    public void createTooltip(TooltipMakerAPI tooltip, boolean expanded) {
        float pad = 10f;
        float small = 5f;
        Color highlight = Misc.getHighlightColor();

        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        SectorEntityToken artillery = getRelatedEntity();
        CampaignAttackScript script = getScript(artillery);

        if (script == null) return;

        boolean isDiscoverable = artillery.isDiscoverable();
        String artyNamy = isDiscoverable ? "artillery" : artillery.getName();
        String name = "In " + artyNamy + " range";
        String disposition = artillery.getFaction().getRelationshipLevel(player.getFaction()).getDisplayName().toLowerCase();
        SectorEntityToken focus = artillery.getOrbitFocus();
        String focusName = focus != null && !isDiscoverable ? focus.getName() : "an unknown location";
        boolean isHostile = script.isHostileTo(player);
        String willOrWont = isHostile ? "will target you" : "will not target you";
        Color relColour = isHostile ? Misc.getNegativeHighlightColor() : Misc.getRelColor(artillery.getFaction().getRelationship(player.getFaction().getId()));
        Color willOrWontColour = isHostile ? Misc.getNegativeHighlightColor() : Misc.getPositiveHighlightColor();
        boolean isOrbitingPlanet = artillery.getOrbit() != null && artillery.getOrbitFocus() instanceof PlanetAPI;

        tooltip.addTitle(name);

        if (script.isInSafeSpot(player))
            tooltip.addPara("You are in a safe spot and will not be targeted by artillery.", highlight, pad);

        Color[] hlColours = new Color[]{artillery.getFaction().getColor(), relColour, willOrWontColour};

        if (isOrbitingPlanet) tooltip.addPara("The artillery orbiting %s is controlled by a %s faction.\n" +
                "It %s if your location is known.", pad, hlColours, focusName, disposition, willOrWont);
        else tooltip.addPara("The artillery is controlled by a %s faction.\n" +
                "It %s if your location is known.", pad, hlColours, disposition, willOrWont);

        if (isDiscoverable) return;

        switch (script.getType()) {
            case TYPE_RAILGUN:
                tooltip.addPara("The defence platform is armed with a %s.\n" +
                        "It will fire multiple extremely fast projectiles at an extreme range.", pad, highlight, "railgun");
                break;
            case TYPE_MISSILE:
                tooltip.addPara("The defence platform is armed with a %s.\n" +
                        "It will launch long-range target seeking missiles.", pad, highlight, "missile launcher");
                break;
            case TYPE_MORTAR:
                tooltip.addPara("The defence platform is armed with a %s.\n" +
                        "It will bombard targets with a high volume of explosive ordinance at extreme range.", pad, highlight, "mortar");
                break;
        }
    }

    @Override
    public Color getNameColor() {
        Color bad = Misc.getNegativeHighlightColor();
        Color hl = Misc.getHighlightColor();
        Color base = super.getNameColor();

        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        SectorEntityToken artillery = getRelatedEntity();
        CampaignAttackScript script = getScript(artillery);

        if (script == null) return Color.WHITE;

        boolean isSafe = script.isInSafeSpot(player);
        boolean isHostile = script.isHostileTo(player);

        if (isHostile && isSafe)
            return Misc.interpolateColor(base, hl, Global.getSector().getCampaignUI().getSharedFader().getBrightness()); //makes it flash that colour - don't interpolate for fixed colour
        if (isHostile)
            return Misc.interpolateColor(hl, bad, Global.getSector().getCampaignUI().getSharedFader().getBrightness()); //makes it flash that colour - don't interpolate for fixed colour
        return base;
    }

    @Override
    public float getMaxEffectRadius(Vector2f locFrom) {
        return getRingParams().bandWidthInEngine;
    }

    @Override
    public float getMinEffectRadius(Vector2f locFrom) {
        return 0f;
    }

    @Override
    protected float getMaxRadiusForContains() {
        return getRingParams().bandWidthInEngine;
    }

    @Override
    protected float getMinRadiusForContains() {
        return 0f;
    }

    public boolean isTooltipExpandable() {
        return true;
    }

    public float getTooltipWidth() {
        return 350f;
    }

    public String getEffectCategory() {
        return "ringsystem-like";
    }
}