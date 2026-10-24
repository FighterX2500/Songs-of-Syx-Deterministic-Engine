package sosdet.report;

/** Where a city report's values come from: the game adapter reads the live city. */
public interface CitySource {

    ReportLayout layout();

    /** The faction whose city it is. */
    int faction();

    /** Writes every field's values, in the layout's slots. */
    void fill(double[] values);

    /** The index in a field of a game object's value, such as a boost value's; -1 when the report has none for it. */
    int indexOf(Field f, Object key);
}
