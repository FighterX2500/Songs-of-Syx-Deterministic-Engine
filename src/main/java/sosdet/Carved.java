package sosdet;

/**
 * What {@link Engine#carve} did (SOS-186, SOSGSGMP docs/plan/hot-join.md §3).
 *
 * @param region the city-state's new region, with no owner yet
 * @param home   the region its centre was carved out of
 * @param newNpc the NPC faction the home region went to under the split rule (unowned land, or a region that broke
 *               away from a realm of 4 or more), -1 for none
 * @param noSlot a new NPC was wanted, but every faction slot was in use: the home region kept its owner, and the mod
 *               tells the players why
 */
public record Carved(int region, int home, int newNpc, boolean noSlot) {
}
