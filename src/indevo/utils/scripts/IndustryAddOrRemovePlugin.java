package indevo.utils.scripts;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import indevo.ids.Ids;

public class IndustryAddOrRemovePlugin implements EveryFrameScript {

    protected final MarketAPI market;
    protected final boolean remove;
    protected final String industryId;

    protected boolean done = false;

    public IndustryAddOrRemovePlugin(MarketAPI market, String industryId, boolean removeIndustry) {
        this.market = market;
        this.remove = removeIndustry;
        this.industryId = industryId;

    }

    public void advance(float amount) {
        if (market.hasIndustry(industryId) && remove) {
            removeIndustry();
        } else if (!market.hasIndustry(industryId) && !remove) {
            addIndustry();
        }
        setDone();
    }

    public void addIndustry() {
        market.addIndustry(industryId);
        setDone();
    }

    //specific check for industry presence on a planet to remove subindustry only if main is gone
    public void removeIndustry() {
        if (!market.hasIndustry(Ids.PIRATEHAVEN)) {
            market.removeIndustry(industryId, MarketAPI.MarketInteractionMode.REMOTE, false);
        }
    }

    public void setDone() {
        done = true;
    }

    public boolean isDone() {
        return done;
    }

    public boolean runWhilePaused() {
        return true;
    }


}
