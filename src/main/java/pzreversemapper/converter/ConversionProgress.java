package pzreversemapper.converter;

/** A progress notification raised by {@link MapConverter}. */
public record ConversionProgress(String stage, String message, Integer completed, Integer total) {
    /** Percentage in [0, 100], or {@code null} when the stage has no measurable progress. */
    public Integer percent() {
        if (completed == null || total == null || total <= 0) {
            return null;
        }

        // Math.rint rounds half to even, matching .NET Math.Round.
        int value = (int) Math.rint(completed * 100d / total);
        return Math.max(0, Math.min(100, value));
    }
}
