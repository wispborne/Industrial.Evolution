package indevo.industries.petshop.memory;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import indevo.industries.academy.industry.Academy;
import indevo.industries.petshop.industry.PetShop;
import indevo.industries.petshop.listener.PetStatusManager;
import indevo.utils.ModPlugin;
import org.lazywizard.lazylib.MathUtils;

import java.awt.*;
import java.util.ArrayList;

public class Pet {
    public static final float DEFAULT_FEED_INTERVAL = 31f;
    public static final String HULLMOD_DATA_PREFIX = "PET_DATA_ID_";
    public static final float MAX_EFFECT_AFTER_DAYS = 365f;
    public static final float PET_ICON_DIM = 64f;

    public String typeID;
    public String id;
    public String name;
    public float age = 1f;
    public float lifetime;
    public FleetMemberAPI assignedFleetMember = null;
    public IntervalUtil feedInterval = new IntervalUtil(Global.getSector().getClock().getSecondsPerDay() * DEFAULT_FEED_INTERVAL, Global.getSector().getClock().getSecondsPerDay() * DEFAULT_FEED_INTERVAL);
    public String personality;

    private float serveDuration = 0f;

    private boolean isAssigned = false;
    private boolean isStarving = false;
    private boolean isDead = false;
    public Industry storage = null;

    public DeathData deathData = null;

    public Pet(String typeID, String name) {
        this.id = Misc.genUID();
        this.typeID = typeID;
        this.name = name;
        this.lifetime = MathUtils.getRandomNumberInRange(0.9f, 1.1f) * getData().maxLife;

        WeightedRandomPicker<String> personalities = new WeightedRandomPicker<>();
        personalities.addAll(Academy.COLOURS_BY_PERSONALITY.keySet());

        this.personality = personalities.pick();

        if (Global.getSettings().isDevMode()) this.feedInterval = new IntervalUtil(10f, 10f); //10 seconds
    }

    public void store(Industry industry){
        unassign();
        storage = industry;
        ((PetShop) industry).store(this);
    }

    public void removeFromStorage(){
        if (storage != null) {
            PetShop shop = (PetShop) storage;
            shop.removeFromStorage(this);
            storage = null;
        }

    }

    public void assign(FleetMemberAPI toMember) {
        ModPlugin.log("assigning " + name + " variety " + typeID + " to " + toMember.getShipName() + " with source " + toMember.getVariant().getSource().name());

        if (toMember.getVariant().getSource() != VariantSource.REFIT){
            cycleToCustomVariant(toMember);
        }

        this.assignedFleetMember = toMember;
        isAssigned = true;
        serveDuration = 0f;
        toMember.getVariant().addPermaMod(getData().hullmod);
        toMember.getVariant().addTag(HULLMOD_DATA_PREFIX + id);
    }

    public static void cycleToCustomVariant(FleetMemberAPI member) {
        ShipVariantAPI variant = member.getVariant();

        variant = variant.clone();
        variant.setOriginalVariant(null);
        variant.setHullVariantId(Misc.genUID());
        variant.setSource(VariantSource.REFIT);
        member.setVariant(variant, false, true);
    }

    public void unassign() {
        if (assignedFleetMember == null) return;

        serveDuration = 0f;
        ShipVariantAPI variant = assignedFleetMember.getVariant();
        String hullmod = getData().hullmod;

        variant.removePermaMod(hullmod);
        variant.removeMod(hullmod);

        for (String s : new ArrayList<>(assignedFleetMember.getVariant().getTags())) {
            if (s.startsWith(HULLMOD_DATA_PREFIX)) assignedFleetMember.getVariant().getTags().remove(s);
        }

        assignedFleetMember = null;
        isAssigned = false;
    }

    public PetData getData() {
        return PetDataRepo.get(typeID);
    }

    public void advance(float amt) {
        if (isDead) return;

        boolean notInCryo = storage != null && !Commodities.BETA_CORE.equals(storage.getAICoreId());
        float dayAmt = Global.getSector().getClock().convertToDays(amt);

        if (notInCryo || isAssigned){
            age += dayAmt;

            if (age > lifetime) {
                PetStatusManager.getInstance().reportPetDied(this, PetStatusManager.PetDeathCause.NATURAL);
            }
        }

        if (!isAssigned) return; //can't be active if in storage so it doesn't double age

        feedInterval.advance(amt);
        serveDuration += dayAmt;

        if (feedInterval.intervalElapsed()) {
            PetStatusManager manager = PetStatusManager.getInstance();
            boolean hasFed = manager.feed(this);

            if (!hasFed && isStarving) manager.reportPetDied(this, PetStatusManager.PetDeathCause.STARVED);
            else if (!hasFed) {
                isStarving = true;
                manager.reportPetStarving(this);
            } else if (isStarving) isStarving = false;
        }
    }

    public boolean isDead() {
        return isDead;
    }

    public boolean isStarving() {
        return isStarving;
    }

    public boolean isAssigned() {
        return isAssigned;
    }

    public void setDead(PetStatusManager.PetDeathCause cause) {
        isDead = true;
        generateDeathData(cause);
        unassign();
        removeFromStorage();
    }

    public float getEffectFract() {
        return Math.min(1f, serveDuration / MAX_EFFECT_AFTER_DAYS);
    }

    public String getAgeString(){
        int years = (int) Math.ceil(age / 364f);
        int months = (int) Math.ceil(age / 31f);

        if (age < 31) return Misc.getStringForDays((int) Math.ceil(age));
        else if (age < 365) return months + (months <= 1 ? " month" : " months");
        else return years + (years <= 1 ? " year" : " years");
    }

    public DeathData generateDeathData(PetStatusManager.PetDeathCause cause){
        //String name, float date, int age, float serveTime, PetStatusManager.PetDeathCause cause, boolean inStorage, String marketName, String shipName
        deathData = new DeathData(name,
                Global.getSector().getClock().getDateString(),
                age,
                serveDuration,
                cause,
                storage != null,
                (storage != null ? storage.getMarket().getName() : null),
                (assignedFleetMember != null ? assignedFleetMember.getShipName() : null));

        return deathData;
    }

    public void addHullmodDescriptionSection(TooltipMakerAPI tooltip) {
        float opad = 10f;
        float spad = 3f;
        Color hl = Misc.getHighlightColor();

        tooltip.addSectionHeading("Pet Data", Alignment.MID, opad);

        float padding = 5f;
        CustomPanelAPI panel = Global.getSettings().createCustom(tooltip.getWidthSoFar() - PET_ICON_DIM, 300f, null);
        TooltipMakerAPI textElement = panel.createUIElement(tooltip.getWidthSoFar() - PET_ICON_DIM, 100f, false);
        textElement.addPara("Name: %s", spad, hl, name);
        textElement.addPara("Species: %s", spad, hl, getData().species);
        textElement.addPara("Disposition: %s", spad, Academy.COLOURS_BY_PERSONALITY.get(personality), personality.toLowerCase());
        textElement.addPara("Age: %s", spad, hl, getAgeString());
        textElement.addPara("Status: %s", spad, isStarving() ? Misc.getNegativeHighlightColor() : hl, isStarving() ? "Starving" : "Well fed and happy");
        panel.addUIElement(textElement).inTL(-padding, 0f); //WHY, WHY THE PADDING ALEX??? WHY????
        panel.getPosition().setSize(tooltip.getWidthSoFar(), textElement.getHeightSoFar()); //scale to correct size

        TooltipMakerAPI imageElement = panel.createUIElement(PET_ICON_DIM,PET_ICON_DIM, false);
        imageElement.addImage(getData().icon, PET_ICON_DIM, 0f);
        panel.addUIElement(imageElement).rightOfTop(textElement, padding);
        tooltip.addCustom(panel, padding);

        tooltip.addPara("Dietary options: ", opad);
        for (String commodity : getData().foodCommodities) {
            String name = Global.getSettings().getCommoditySpec(commodity).getName();
            tooltip.addPara(BaseIntelPlugin.BULLET + " " + name, spad);
        }

        tooltip.addSectionHeading("Description", Alignment.MID, opad);
        tooltip.addPara(getData().desc, opad);
    }
}
