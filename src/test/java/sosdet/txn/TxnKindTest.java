package sosdet.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TxnKindTest {

    @Test
    void aFollowerSkipsOnlyTheSourceCitysPeopleAndDivisions() {
        Set<TxnKind> skipped = EnumSet.noneOf(TxnKind.class);
        for (TxnKind k : TxnKind.values()) {
            if (!k.followerApplies()) {
                skipped.add(k);
            }
        }
        assertEquals(EnumSet.of(TxnKind.SOLDIER_RETURNS, TxnKind.RETIREE, TxnKind.REL_SOLDIER, TxnKind.REL_DEATH,
                TxnKind.REL_HUMANOID, TxnKind.ARMY_SUPPLY_DIV, TxnKind.ARMY_SUPPLY_RECRUIT, TxnKind.GARRISON_RESOLVE,
                TxnKind.GARRISON_RESOLVE_PEOPLE, TxnKind.REMOVE_PEOPLE), skipped,
                "trade, credits and invasions reach a follower's city; the source city's soldiers do not");
    }
}
