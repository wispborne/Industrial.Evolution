package indevo.industries.embassy.listeners;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CommDirectoryEntryAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.EconomyTickListener;
import com.fs.starfarer.api.characters.ImportantPeopleAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import exerelin.campaign.DiplomacyManager;
import indevo.ids.Ids;
import indevo.industries.embassy.AmbassadorItemHelper;
import indevo.industries.embassy.industry.Embassy;
import indevo.utils.helper.IndustryHelper;
import indevo.utils.helper.Settings;
import indevo.utils.helper.StringHelper;
import indevo.utils.timers.NewDayListener;
import org.apache.log4j.Logger;

import java.awt.*;
import java.util.List;
import java.util.*;

public class AmbassadorPersonManager implements EconomyTickListener, NewDayListener {
    private static final float NO_PENALTY = 0f;

    private final Map<FactionAPI, Float> lastDayRep = new HashMap<>();
    private final Map<FactionAPI, Float> thisMonthPositiveRepChanges = new HashMap<>();
    private final Map<FactionAPI, Float> thisMonthPenaltyRepChanges = new HashMap<>();

    public static final Logger log = Global.getLogger(AmbassadorPersonManager.class);

    private static void log(String Text) {
        log.info(Text);
    }

    public static AmbassadorPersonManager getInstance() {
        return Global.getSector().getListenerManager().getListeners(AmbassadorPersonManager.class).get(0);
    }

    @Override
    public void reportEconomyTick(int iterIndex) {
    }

    @Override
    public void reportEconomyMonthEnd() {
        if (!Settings.EMBASSY) return;

        Map<FactionAPI, Float> mergedMap = new HashMap<>();

        for (Map.Entry<FactionAPI, Float> e : thisMonthPenaltyRepChanges.entrySet()) {
            adjustRelationship(e.getKey(), Global.getSector().getPlayerFaction(), e.getValue());
            addOrIncrement(mergedMap, e.getKey(), e.getValue());
        }

        for (Map.Entry<FactionAPI, Float> e : thisMonthPositiveRepChanges.entrySet()) {
            addOrIncrement(mergedMap, e.getKey(), e.getValue());
        }

        if (!mergedMap.isEmpty()) {
            MessageIntel intel = new MessageIntel(StringHelper.getString(Ids.EMBASSY, "embassyRepChange"), Misc.getTextColor());
            for (Map.Entry<FactionAPI, Float> e : mergedMap.entrySet()) {
                FactionAPI faction = e.getKey();
                String delta = StringHelper.getFloatToIntStrx100(e.getValue());
                String deltaStr = e.getValue() > 0f ? "+" + delta : delta;
                Pair<String, Color> repInt = StringHelper.getRepIntTooltipPair(faction);
                String factionName = faction.getDisplayName();
                Color deltaColor = e.getValue() >= 0 ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();

                intel.addLine(BaseIntelPlugin.BULLET + factionName + ": " + deltaStr + "  " + repInt.one,
                        Misc.getTextColor(),
                        new String[]{factionName + ": ", deltaStr, repInt.one},
                        faction.getColor(),
                        deltaColor,
                        repInt.two
                );
            }

            intel.setIcon(Global.getSettings().getSpriteName("IndEvo", "reputation"));
            intel.setSound(BaseIntelPlugin.getSoundStandardPosting());
            Global.getSector().getCampaignUI().addMessage(intel);
        }

        thisMonthPositiveRepChanges.clear();
        thisMonthPenaltyRepChanges.clear();
    }

    @Override
    public void onNewDay() {
        if (!Settings.EMBASSY) return;
        logRepChange();
        updateLastDayRep();
        getWarConsequences();
    }

    public static void adjustRelationship(FactionAPI baseFaction, FactionAPI alignedFaction, float delta) {
        String alignedFactionId = alignedFaction.getId();
        if (Global.getSettings().getModManager().isModEnabled("nexerelin")) {
            DiplomacyManager.adjustRelations(baseFaction, alignedFaction, delta, null, null, null);
        } else {
            baseFaction.adjustRelationship(alignedFactionId, delta);
        }
    }

    private void logRepChange() {

        Map<FactionAPI, Float> repMultMap = getFactionRepMultMap();

        for (Map.Entry<FactionAPI, Float> entry : lastDayRep.entrySet()) {

            FactionAPI playerFaction = Global.getSector().getPlayerFaction();
            FactionAPI faction = entry.getKey();

            if (!repMultMap.containsKey(faction)) continue;

            float lastDayStanding = entry.getValue();
            float currentStanding = faction.getRelationship(playerFaction.getId());
            float penaltyMult = repMultMap.get(faction);

            if (currentStanding < lastDayStanding) {
                //total penalty is delta between the two /2 * embassyFactor
                float delta = lastDayStanding - currentStanding;
                float penalty = (float) -Math.ceil((delta * penaltyMult * 0.5f * 100f)) / 100f;

                addOrIncrement(thisMonthPenaltyRepChanges, faction, penalty);
            }
        }
    }

    private void updateLastDayRep() {
        //need to update the lastDayFactionRep list for the next day
        FactionAPI playerFaction = Global.getSector().getPlayerFaction();
        lastDayRep.clear();

        for (Map.Entry<FactionAPI, Float> entry : getFactionRepMultMap().entrySet()) {
            FactionAPI faction = entry.getKey();
            lastDayRep.put(faction, playerFaction.getRelationship(faction.getId()));
        }
    }

    private void getWarConsequences() {
        FactionAPI playerFaction = Global.getSector().getPlayerFaction();

        for (MarketAPI market : getPlayerMarketsWithEmbassy()) {
            if (!hasAmbassador(market) || !market.isPlayerOwned()) continue;

            PersonAPI person = AmbassadorPersonManager.getAmbassador(market);

            if (getListOfIncativeFactions().contains(getAmbassador(market).getFaction())) {
                displayMessage("ambassadorVacated", "ambassadorFactionEradicatedFlavour", person, market, NO_PENALTY);
                AmbassadorPersonManager.deleteAmbassador(market);
                market.getIndustry(Ids.EMBASSY).setSpecialItem(null);
                continue;
            }

            if (getAmbassador(market).getFaction().isHostileTo(playerFaction)) {
                displayMessage("ambassadorVacated", "ambassadorFactionAliveFlavour", person, market, NO_PENALTY);
                returnToHome(person, market);
                continue;
            }

            //has the ambassador been disallowed?
            String memKey = AmbassadorItemHelper.getFactionMemoryKey(getAmbassador(market).getFaction());
            if (Global.getSector().getMemory().contains(memKey) && !Global.getSector().getMemory().getBoolean(memKey)) {

                displayMessage("ambassadorVacated", "ambassadorRecalledFlavour", person, market, NO_PENALTY);
                AmbassadorPersonManager.deleteAmbassador(market);
                market.getIndustry(Ids.EMBASSY).setSpecialItem(null);
            }
        }
    }

    public static void ambassadorPresenceAllowedCheck(MarketAPI market) {
        //remove an ambassador from a market if ownership changes or it no longer meets the conditions
        if (!market.isPlayerOwned() && hasAmbassador(market)) {
            if (!getAmbassador(market).getFaction().getId().equals(market.getFactionId())) {
                deleteAmbassador(market);
                log("removed ambassador at " + market.getName() + ", faction mismatch");
            }

            if (market.getSize() < 5) {
                deleteAmbassador(market);
                log("removed ambassador at " + market.getName() + ", size condition not met");
            }

            if (hasAmbassador(market)) {
                String memKey = AmbassadorItemHelper.getFactionMemoryKey(getAmbassador(market).getFaction());
                if (Global.getSector().getMemory().contains(memKey) && !Global.getSector().getMemory().getBoolean(memKey)) {
                    deleteAmbassador(market);
                    log("removed ambassador at " + market.getName() + ", manual presence override");
                }
            }
        }

        if (market.isPlayerOwned() && hasAmbassador(market) && !market.hasIndustry(Ids.EMBASSY)) {
            deleteAmbassador(market);
            log("removed ambassador at " + market.getName() + ", no Embassy");
        }

        if (market.isPlayerOwned() && hasAmbassador(market) && market.hasIndustry(Ids.EMBASSY) && market.getIndustry(Ids.EMBASSY).getSpecialItem() == null) {
            deleteAmbassador(market);
            log("removed ambassador at " + market.getName() + ", no specItem but ambassador present");
        }
    }

    public static void createAmbassadorsOnMarkets(MarketAPI market) {
        if (market == null) {
            return;
        }

        //get all eligible markets
        String memKey = AmbassadorItemHelper.getFactionMemoryKey(market.getFaction());
        if (Global.getSector().getMemory().contains(memKey)
                && !Global.getSector().getMemory().getBoolean(memKey)) {
            return;
        }

        if (AmbassadorItemHelper.getAllowedFactionID().contains(market.getFaction().getId())
                && market.getSize() >= 5
                && getAmbassador(market) == null
                && !market.isPlayerOwned()) {

            //if market is eligible - valid faction and > size 5
            log("creating ambassador at " + market.getName());
            createAmbassador(market, true, null);
        }
    }

    public static void displayMessage(String mainText, String flavourText, PersonAPI person, float repDropAmount) {
        displayMessage(mainText, flavourText, person, null, repDropAmount);
    }

    public static void displayRepChange(String mainText, String flavourText, PersonAPI person, MarketAPI market, float repDropAmount) {
    }

    public static void displayMessage(String mainText, String flavourText, PersonAPI person, MarketAPI market, float repDropAmount) {
        Color color = Misc.getTextColor();
        FactionAPI faction = person.getFaction();

        Pair<String, Color> repInt = StringHelper.getRepIntTooltipPair(faction);
        Map<String, String> toReplace = new HashMap<>();
        if (market != null) toReplace.put("$marketName", market.getName());
        toReplace.put("$factionName", faction.getDisplayName());
        toReplace.put("$personName", person.getNameString());
        toReplace.put("$heOrShe", StringHelper.getHeOrShe(person));
        toReplace.put("$decreasedByPenalty", StringHelper.getString(Ids.EMBASSY, "decreasedByInt") + StringHelper.getFloatToIntStrx100(-repDropAmount));

        String message = StringHelper.getStringAndSubstituteTokens(Ids.EMBASSY, mainText, toReplace);
        String[] highlights = new String[]{toReplace.get("$factionName"), toReplace.get("$personName")};
        String flavour = StringHelper.getString(Ids.EMBASSY, flavourText);

        MessageIntel intel = new MessageIntel(message, color, highlights, faction.getColor());
        if (flavourText != null) intel.addLine(flavour, color);
        if (repDropAmount != 0f) {
            intel.addLine(BaseIntelPlugin.BULLET + StringHelper.getStringAndSubstituteTokens(Ids.EMBASSY, "decreaseMessage", toReplace), color, new String[]{toReplace.get("$factionName"), toReplace.get("$decreasedByPenalty")}, faction.getColor(), Misc.getNegativeHighlightColor());
            intel.addLine(BaseIntelPlugin.BULLET + StringHelper.getString(Ids.EMBASSY, "currentAt"), color, new String[]{repInt.one}, repInt.two);
            Global.getSoundPlayer().playUISound("ui_rep_drop", 1, 1);
        } else intel.setSound(BaseIntelPlugin.getSoundMinorMessage());
        intel.setIcon(faction.getCrest());

        if (market != null)
            Global.getSector().getCampaignUI().addMessage(intel, CommMessageAPI.MessageClickAction.COLONY_INFO, market);
        else Global.getSector().getCampaignUI().addMessage(intel, CommMessageAPI.MessageClickAction.INTEL_TAB);
    }

    public static List<MarketAPI> getPlayerMarketsWithEmbassy() {
        List<MarketAPI> list = new ArrayList<>();

        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isPlayerOwned() && hasAmbassador(market) && market.hasIndustry(Ids.EMBASSY)) {
                list.add(market);
            }
        }
        return list;
    }

    public static PersonAPI getAmbassador(MarketAPI market) {
        if (market == null) return null;

        for (CommDirectoryEntryAPI dir : market.getCommDirectory().getEntriesCopy()) {
            if (dir.getType() != CommDirectoryEntryAPI.EntryType.PERSON) continue;
            PersonAPI person = (PersonAPI) dir.getEntryData();
            if (person.getPostId().equals(AmbassadorItemHelper.POST_AMBASSADOR))
                return person;
        }
        return null;
    }

    public static boolean hasAmbassador(MarketAPI market) {
        return getAmbassador(market) != null;
    }

    public static boolean removeAmbassadorFromMarket(MarketAPI market) {
        PersonAPI person = getAmbassador(market);
        if (person == null) return false;

        market.getCommDirectory().removePerson(person);
        market.removePerson(person);
        return true;
    }

    public static boolean deleteAmbassador(MarketAPI market) {
        PersonAPI person = getAmbassador(market);
        if (person == null) return false;

        market.getCommDirectory().removePerson(person);
        market.removePerson(person);
        Global.getSector().getImportantPeople().removePerson(person);
        return true;
    }

    public static boolean addAmbassadorToMarket(PersonAPI ambassador, MarketAPI market) {
        if (ambassador == null) {
            return false;
        }
        ImportantPeopleAPI ip = Global.getSector().getImportantPeople();

        market.getCommDirectory().addPerson(ambassador);
        market.addPerson(ambassador);
        ip.returnPerson(ambassador, "permanent_staff");
        ip.getData(ambassador).getLocation().setMarket(market);
        ip.checkOutPerson(ambassador, "permanent_staff");

        return true;
    }

    public static PersonAPI createAmbassador(MarketAPI market, boolean noDuplicate, String factionIdOverride) {
        if (noDuplicate && hasAmbassador(market))
            return null;


        ImportantPeopleAPI ip = Global.getSector().getImportantPeople();
        String rankId = AmbassadorItemHelper.RANK_AMBASSADOR;

        PersonAPI person = market.getFaction().createRandomPerson();
        person.setRankId(rankId);
        person.setPostId(AmbassadorItemHelper.POST_AMBASSADOR);
        if (factionIdOverride != null) person.setFaction(factionIdOverride);

        market.getCommDirectory().addPerson(person);
        market.addPerson(person);
        ip.addPerson(person);
        ip.getData(person).getLocation().setMarket(market);
        ip.checkOutPerson(person, "permanent_staff");
        person.addTag("amb_" + market.getId());

        /*person.setVoice(Voices.OFFICIAL);
        person.addTag(Tags.CONTACT_TRADE);*/

        return person;
    }

    public static MarketAPI getOriginalMarket(PersonAPI ambassador) {
        for (String tag : ambassador.getTags()) {
            if (tag.contains("amb_")) {
                return Global.getSector().getEconomy().getMarket(tag.substring(4));
            }
        }
        return null;
    }

    public void reportEmbassyRepChange(FactionAPI faction, float delta) {
        addOrIncrement(thisMonthPositiveRepChanges, faction, delta);
    }

    private void addOrIncrement(Map<FactionAPI, Float> map, FactionAPI key, Float delta) {
        if (map.containsKey(key)) map.put(key, map.get(key) + delta);
        else map.put(key, delta);
    }

    public static List<FactionAPI> getListOfIncativeFactions() {
        List<FactionAPI> currentFactionList = new ArrayList<>();
        List<FactionAPI> inactiveFactionList = new ArrayList<>();
        List<FactionAPI> startFactionList = new ArrayList<>(Global.getSector().getAllFactions());

        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!currentFactionList.contains(market.getFaction())) {
                currentFactionList.add(market.getFaction());
            }
        }

        if (!currentFactionList.containsAll(startFactionList)) {
            for (FactionAPI faction : startFactionList) {
                if (!currentFactionList.contains(faction)) {
                    inactiveFactionList.add(faction);
                }
            }
        }
        return inactiveFactionList;
    }

    public static void returnToHome(PersonAPI ambassador, MarketAPI fromMarket) {
        if (AmbassadorPersonManager.removeAmbassadorFromMarket(fromMarket)) {
            MarketAPI originalMarket = AmbassadorPersonManager.getOriginalMarket(ambassador);

            AmbassadorPersonManager.deleteAmbassador(originalMarket);
            AmbassadorPersonManager.addAmbassadorToMarket(ambassador, originalMarket);

            fromMarket.getIndustry(Ids.EMBASSY).setSpecialItem(null);
        }
    }

    private Map<FactionAPI, Float> getFactionRepMultMap() {
        Map<FactionAPI, Float> repMultMap = new HashMap<>();

        for (MarketAPI market : getPlayerMarketsWithEmbassy()) {
            Embassy emb = (Embassy) market.getIndustry(Ids.EMBASSY);
            FactionAPI faction = emb.alignedFaction;
            float aiCoreBonus = IndustryHelper.getAiCoreIdNotNull(emb).equals(Commodities.ALPHA_CORE) ? 0.5f : 1f;

            if (repMultMap.containsKey(faction)) {
                repMultMap.put(faction, repMultMap.get(faction) + 1 * aiCoreBonus);
            } else {
                repMultMap.put(getAmbassador(market).getFaction(), 1 * aiCoreBonus);
            }
        }

        return repMultMap;
    }

    public boolean isDone() {
        return false;
    }

    public static MarketAPI getClosestEmptyEmbassyToMarket(MarketAPI market) {
        Set<MarketAPI> marketSet = new HashSet<>();
        for (MarketAPI anyMarket : Global.getSector().getEconomy().getMarketsCopy()) {
            if (anyMarket.isPlayerOwned()
                    && anyMarket.hasIndustry(Ids.EMBASSY)
                    && anyMarket.getIndustry(Ids.EMBASSY).getSpecialItem() == null
            ) marketSet.add(anyMarket);
        }

        return IndustryHelper.getClosestPlayerMarketWithIndustryFromSet(market, Ids.EMBASSY, marketSet);
    }

    public static class checkAmbassadorPresence extends BaseCampaignEventListener {
        public checkAmbassadorPresence() {
            super(false);
        }

        @Override
        public void reportPlayerOpenedMarket(MarketAPI market) {
            AmbassadorPersonManager.ambassadorPresenceAllowedCheck(market);
            AmbassadorPersonManager.createAmbassadorsOnMarkets(market);
        }
    }

    public static class moveAmbassadorToMarket implements EveryFrameScript {
        private boolean isDone = false;
        private final MarketAPI source;
        private final MarketAPI target;

        public moveAmbassadorToMarket(MarketAPI source, MarketAPI target) {
            this.source = source;
            this.target = target;
        }

        @Override
        public boolean isDone() {
            return isDone;
        }

        @Override
        public boolean runWhilePaused() {
            return true;
        }

        @Override
        public void advance(float amount) {
            if (!isDone) {

                MarketAPI market = source;
                Embassy embassy = market.hasIndustry(Ids.EMBASSY) ? (Embassy) market.getIndustry(Ids.EMBASSY) : null;

                MarketAPI targetMarket = target;
                Embassy targetEmbassy = targetMarket != null && targetMarket.hasIndustry(Ids.EMBASSY) ? (Embassy) targetMarket.getIndustry(Ids.EMBASSY) : null;

                //just to make super sure, check everything again
                if (embassy != null
                        && AmbassadorPersonManager.hasAmbassador(market)
                        && targetMarket != null
                        && !AmbassadorPersonManager.hasAmbassador(targetMarket)
                        && targetEmbassy != null
                        && targetEmbassy.getSpecialItem() == null) {

                    //get stuff
                    SpecialItemData spec = embassy.getAmbassadorItemData();
                    PersonAPI pers = AmbassadorPersonManager.getAmbassador(market);

                    //remove the amb
                    embassy.setAmbassadorItemData(null);
                    AmbassadorPersonManager.removeAmbassadorFromMarket(market);

                    //add the amb to new loc
                    targetEmbassy.setAmbassadorItemData(spec);
                    AmbassadorPersonManager.addAmbassadorToMarket(pers, targetMarket);
                }

                isDone = true;
            }
        }
    }
}
