package indevo.items.consumables;

import indevo.ids.Ids;
import indevo.ids.ItemIds;

import java.util.HashMap;
import java.util.Map;

public class ItemAbilityHelper {
    public static Map<String, String> ID_MAP = new HashMap<String, String>() {{
        put(ItemIds.CONSUMABLE_DRONES, Ids.ABILITY_DRONES);
        put(ItemIds.CONSUMABLE_LOCATOR, Ids.ABILITY_LOCATOR);
        put(ItemIds.CONSUMABLE_NANITES, Ids.ABILITY_NANITES);
        put(ItemIds.CONSUMABLE_SCOOP, Ids.ABILITY_SCOOP);
        put(ItemIds.CONSUMABLE_SPIKE, Ids.ABILITY_SPIKE);
        put(ItemIds.CONSUMABLE_STABILIZER, Ids.ABILITY_STABILIZER);
        put(ItemIds.CONSUMABLE_SUPERCHARGER, Ids.ABILITY_SUPERCHARGER);
        put(ItemIds.CONSUMABLE_DECOY, Ids.ABILITY_DECOY);
        put(ItemIds.CONSUMABLE_SPOOFER, Ids.ABILITY_SPOOFER);
    }};

    public static String toggle(String s) {
        if (ID_MAP.values().contains(s)) {
            for (Map.Entry<String, String> e : ID_MAP.entrySet()) {
                if (e.getValue().equals(s)) return e.getKey();
            }
        }

        return ID_MAP.get(s);
    }
}
