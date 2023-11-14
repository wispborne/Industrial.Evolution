package indevo.industries.worldwonder.industry;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketImmigrationModifier;
import com.fs.starfarer.api.campaign.listeners.EconomyTickListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.population.PopulationComposition;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import indevo.ids.Ids;

import java.awt.*;

public class WorldWonder extends BaseIndustry implements MarketImmigrationModifier, EconomyTickListener {

    public static final float IMMIGRATION_BONUS = 10f;
    public static final float STABILITY_BONUS = 1f;

    public static final String HAS_AWARDED_SP = "$IndEvo_hasAwardedSP";
    public static final String ALTERNATE_VISUAL = "$IndEvo_alternateWonderVisual";

    public static final String TAG_SYSTEM_HAS_WONDER = "IndEvo_hasWonder";

    public boolean isAlternateVisual = false;

    @Override
    public String getCurrentImage() {
        if (market.getMemoryWithoutUpdate().getBoolean(ALTERNATE_VISUAL) || isAlternateVisual) {
            if (getAlternateImagePath() != null) return getAlternateImagePath();
        }

        return super.getCurrentImage();
    }

    public String getAlternateImagePath() {
        String path = null;

        try {
            path = Global.getSettings().getSpriteName("IndEvo", spec.getData());
        } catch (RuntimeException e) {
            return null;
        }

        return path;
    }

    public boolean hasAlternateImage() {
        return getAlternateImagePath() != null;
    }

    @Override
    public void apply() {
        super.apply(true);

        if (isFunctional() && currTooltipMode != IndustryTooltipMode.ADD_INDUSTRY) {
            Global.getSector().getListenerManager().addListener(this, true);
            market.getStarSystem().addTag(TAG_SYSTEM_HAS_WONDER);

            for (MarketAPI market : Misc.getMarketsInLocation(this.market.getStarSystem(), this.market.getFactionId())) {
                if (!market.hasCondition(Ids.COND_WORLD_WONDER)) market.addCondition(Ids.COND_WORLD_WONDER);
            }
        }
    }

    @Override
    public void finishBuildingOrUpgrading() {
        super.finishBuildingOrUpgrading();

        MemoryAPI memory = market.getMemoryWithoutUpdate();
        if (!memory.getBoolean(HAS_AWARDED_SP) && market.isPlayerOwned()) {
            Global.getSector().getCampaignUI().addMessage("Constructing the " + getCurrentName() + " on " + market.getName() + " has awarded you an additional story point.",
                    Misc.getPositiveHighlightColor());
            Global.getSector().getPlayerStats().addStoryPoints(1);
            memory.set(HAS_AWARDED_SP, true);
        }
    }

    @Override
    protected void upgradeFinished(Industry previous) {
        super.upgradeFinished(previous);
    }

    @Override
    public boolean canInstallAICores() {
        return false;
    }

    @Override
    public void unapply() {
        super.unapply();
        market.getStarSystem().removeTag(TAG_SYSTEM_HAS_WONDER);
        Global.getSector().getListenerManager().removeListener(this);
    }

    public void modifyIncoming(MarketAPI market, PopulationComposition incoming) {
        if (isFunctional()) {
            incoming.add(Factions.INDEPENDENT, IMMIGRATION_BONUS);
            incoming.getWeight().modifyFlat(getModId(), IMMIGRATION_BONUS, getNameForModifier());
        }
    }

    @Override
    public void reportEconomyTick(int iterIndex) {

    }

    @Override
    public void reportEconomyMonthEnd() {
        RecentUnrest unrest = RecentUnrest.get(market);

        if (unrest.getPenalty() > 0) {
            unrest.add(-1, getNameForModifier());
        }
    }

    @Override
    protected void addRightAfterDescriptionSection(TooltipMakerAPI tooltip, IndustryTooltipMode mode) {
        super.addRightAfterDescriptionSection(tooltip, mode);
        MemoryAPI memory = market.getMemoryWithoutUpdate();

        if (IndustryTooltipMode.ADD_INDUSTRY.equals(mode) && !memory.getBoolean(HAS_AWARDED_SP))
            tooltip.addPara("Constructing a World Symbol will award you a Story Point.", Misc.getPositiveHighlightColor(), 10f);
        else if (IndustryTooltipMode.ADD_INDUSTRY.equals(mode) && memory.getBoolean(HAS_AWARDED_SP))
            tooltip.addPara("A story point has already been awarded for building a Symbol on this world.", Misc.getHighlightColor(), 10f);

        //if(IndustryTooltipMode.ADD_INDUSTRY.equals(mode) && isAvailableToBuild()) tooltip.addPara("Only one can exist at a time", 10f, Misc.getHighlightColor());
    }

    @Override
    protected void addPostDemandSection(TooltipMakerAPI tooltip, boolean hasDemand, IndustryTooltipMode mode) {
        if (mode != IndustryTooltipMode.NORMAL || isFunctional()) {
            Color h = Misc.getHighlightColor();
            float opad = 10f;

            tooltip.addPara("Population growth bonus: %s", opad, h, "+" + Math.round(IMMIGRATION_BONUS));
        }
    }
}
