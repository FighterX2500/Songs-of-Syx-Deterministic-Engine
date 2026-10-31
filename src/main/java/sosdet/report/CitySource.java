package sosdet.report;

/** Where a city report's values come from: the game adapter reads the live city. */
public interface CitySource {

    ReportLayout layout();

    /** The faction whose city it is. */
    int faction();

    /** The game's human slots, in slot order (ADR 0004): the factions with a city report. */
    int[] humans();

    /** True for a human slot whose human was removed (SOS-66): it sends no report and nothing runs for it. */
    default boolean eliminated(int slot) {
        return false;
    }

    default boolean retired(int slot) {
        return false;
    }

    /** The human in {@code slot} is retired, or eliminated, again in a game loaded from before that. */
    default void retiredAgain(int slot, boolean eliminated) {
    }

    /** Writes every field's values, in the layout's slots. */
    void fill(double[] values);

    /** The index in a field of a game object's value, such as a boost value's; -1 when the report has none for it. */
    int indexOf(Field f, Object key);
}
