package indevo.abilities.splitfleet.fleetAssignmentAIs;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import indevo.abilities.splitfleet.fleetManagement.Behaviour;

import java.util.List;

public class EscortAssignmentAI extends BaseSplinterFleetAssignmentAIV2 {
    //attacks equal-strength hostile fleets in range, and attacks fleeing or attacking forces regardless of size

    public EscortAssignmentAI(CampaignFleetAPI fleet) {
        super();
        this.fleet = fleet;
        giveInitialAssignments();
    }

    public void setFlags() {
        MemoryAPI splinterFleetMemory = fleet.getMemoryWithoutUpdate();
        splinterFleetMemory.set(MemFlags.CAN_ONLY_BE_ENGAGED_WHEN_VISIBLE_TO_PLAYER, true);
        splinterFleetMemory.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
    }

    @Override
    protected void pickNext() {
        if (fleet.getAI() == null) return;

        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, playerFleet, ASSIGNMENT_DURATION_FOREVER, "Escorting");
    }

    @Override
    public void advance(float amount) {
        super.advance(amount);

        //if player is heading to fleet and we are on normal behaviour, head towards them
        headToPlayerIfTargettedAssignment();
        engageFleetsPlayerIsTargeting();
        engageVisibleHostileFleetsPlayer();

        checkForJumpActions();
    }

    private void engageFleetsPlayerIsTargeting() {
        if (Behaviour.isBehaviourOverridden(fleet)) return;

        FleetAssignment currentAssignment = fleet.getCurrentAssignment().getAssignment();

        //Default state means we can engage
        if (FleetAssignment.ORBIT_PASSIVE.equals(currentAssignment)) {
            CampaignFleetAPI enemyFleet = null;
            CampaignFleetAPI player = Global.getSector().getPlayerFleet();

            SectorEntityToken token = player.getInteractionTarget();
            if (token instanceof CampaignFleetAPI && ((CampaignFleetAPI) token).isHostileTo(player)) {
                enemyFleet = (CampaignFleetAPI) token;
            }

            if (enemyFleet != null) {
                fleet.clearAssignments();
                clearFlags();
                fleet.addFloatingText("Intercepting " + enemyFleet.getName(), fleet.getFaction().getBaseUIColor(), 1f);
                fleet.addAssignment(FleetAssignment.INTERCEPT, enemyFleet, ASSIGNMENT_DURATION_3_DAY, "Intercepting " + enemyFleet.getName());

                AbilityPlugin eb = fleet.getAbility(Abilities.EMERGENCY_BURN);
                if (eb != null && eb.isUsable()) eb.activate();
            }
        }
    }

    private void engageVisibleHostileFleetsPlayer() {
        if (inBattle || fleet == null) return;

        FleetAssignment currentAssignment = fleet.getCurrentAssignment() != null ? fleet.getCurrentAssignment().getAssignment() : null;

        //Default state means we can engage
        if (FleetAssignment.ORBIT_PASSIVE.equals(currentAssignment)) {
            List<CampaignFleetAPI> visibleFleetList = Misc.getVisibleFleets(fleet, true);
            CampaignFleetAPI enemyFleet = null;

            for (CampaignFleetAPI enemy : visibleFleetList) {
                SectorEntityToken interactionTarget = enemy.getInteractionTarget();

                boolean isFleeing = enemy.getAI() != null && enemy.getAI().isFleeing();
                boolean isTargettingPlayer = interactionTarget != null && interactionTarget.getId().equals(Global.getSector().getPlayerFleet().getId());
                boolean isDefeatable = enemy.getFleetPoints() < (fleet.getFleetPoints() * 1.6);

                if (enemy.isHostileTo(fleet)) {
                    if (isDefeatable) {
                        enemyFleet = enemy;
                        break;
                    } else if (isFleeing || isTargettingPlayer) {
                        enemyFleet = enemy;
                        break;
                    }
                }
            }

            if (enemyFleet != null) {
                fleet.clearAssignments();
                clearFlags();
                fleet.addFloatingText("Attacking " + enemyFleet.getName(), fleet.getFaction().getBaseUIColor(), 1f);
                fleet.addAssignment(FleetAssignment.INTERCEPT, enemyFleet, ASSIGNMENT_DURATION_3_DAY, "Attacking " + enemyFleet.getName());
            }

            //else we are chasing something, or moving to player
        } else if (FleetAssignment.INTERCEPT.equals(currentAssignment)) {
            if (!fleet.getCurrentAssignment().getTarget().isVisibleToSensorsOf(fleet)) {
                fleet.clearAssignments();
                setFlags();
                fleet.addFloatingText("Standing down", fleet.getFaction().getBaseUIColor(), 1f);
                fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, Global.getSector().getPlayerFleet(), ASSIGNMENT_DURATION_FOREVER, "Escorting");
            }
        }
    }

    @Override
    public void reportFleetJumped(CampaignFleetAPI fleet, SectorEntityToken from, JumpPointAPI.JumpDestination to) {
        super.reportFleetJumped(fleet, from, to);
        if (!fleet.isPlayerFleet()) return;

        if (!this.fleet.isInCurrentLocation()) {
            addToJumpList(from, to);
        }
    }

    @Override
    public void reportFleetTransitingGate(CampaignFleetAPI fleet, SectorEntityToken gateFrom, SectorEntityToken gateTo) {
        super.reportFleetTransitingGate(fleet, gateFrom, gateTo);

        if (this.fleet.getAI() == null) return;
        if (!fleet.isPlayerFleet()) return;

        if (!this.fleet.isInCurrentLocation()) {
            addToJumpList(gateFrom, gateTo);
        }
    }
}
