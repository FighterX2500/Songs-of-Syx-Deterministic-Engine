package sosdet.report;

/** Where a city report's values come from: the game adapter reads the live city. */
public interface CitySource {

    ReportLayout layout();

    /** The faction whose city it is. */
    int faction();

    /** Writes every field's values, in the layout's slots. */
    void fill(double[] values);
}
