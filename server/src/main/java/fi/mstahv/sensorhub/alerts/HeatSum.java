package fi.mstahv.sensorhub.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import fi.mstahv.sensorhub.store.HistoryPoint;

/**
 * Degree-days accumulated by a sensor since a given moment, and when a target
 * looks like being reached.
 *
 * <p>The unit comes from the practice this is for: hanging game to tenderise it is
 * measured in <i>vuorokausiastetta</i> — degree-days, temperature multiplied by
 * time. Forty is the usual guideline, so +8 °C for five days and +5 °C for eight
 * days are the same thing. Some prefer sixty for more flavour.
 *
 * <p>Two rules from that practice shape the arithmetic:
 *
 * <ul>
 * <li><b>Frozen meat does not tenderise.</b> Time below zero contributes nothing —
 * and it does not subtract either, since what has already happened does not
 * un-happen.
 * <li>The ideal range is 2–7 °C and above 10 °C is unsuitable, bacteria growing
 * faster than the meat matures. That is a judgement about whether to hang at all,
 * not about the arithmetic, so it is left to the reader — but it is why the UI
 * shows the temperature next to the sum.
 * </ul>
 *
 * @param degreeDays what has accumulated so far
 * @param measuredOver how much time the samples actually span
 * @param recentRate the current speed in degree-days per day, which is simply the
 *        recent average temperature — or the current one when there is only a single
 *        reading. Empty only when there are no readings at all, which is a different
 *        thing from a rate of zero: no data is not the same as a frozen shed, and
 *        what is shown to the reader differs.
 */
public record HeatSum(double degreeDays, Duration measuredOver, Optional<Double> recentRate) {

    /**
     * How much history the current rate is averaged over. Long enough to ride out
     * a day's warm afternoon, short enough to follow a cold front.
     */
    private static final Duration RATE_WINDOW = Duration.ofHours(6);

    /**
     * Below this the rate is treated as no progress at all. A hundredth of a degree
     * would otherwise produce a forecast of decades, which is not a forecast.
     */
    private static final double NEGLIGIBLE_RATE = 0.05;

    public static HeatSum none() {
        return new HeatSum(0, Duration.ZERO, Optional.empty());
    }

    /**
     * @param history the sensor's readings from the counter's start onwards, oldest
     *        first, as the store returns them
     */
    public static HeatSum of(List<HistoryPoint> history) {
        List<HistoryPoint> measured = history.stream()
                .filter(point -> point.temperature() != null)
                .toList();
        if (measured.isEmpty()) {
            return none();
        }
        if (measured.size() == 1) {
            /*
               A counter started a moment ago: no elapsed time, so nothing has
               accumulated — but the temperature right now is a perfectly good
               estimate of the rate, and the alternative is a card that can only say
               "waiting" until the second packet arrives five minutes later.
            */
            return new HeatSum(0, Duration.ZERO,
                    Optional.of(Math.max(0, measured.getFirst().temperature())));
        }

        double degreeHours = 0;
        for (int i = 0; i + 1 < measured.size(); i++) {
            degreeHours += contribution(measured.get(i), measured.get(i + 1));
        }
        Duration span = Duration.between(measured.getFirst().at(), measured.getLast().at());
        return new HeatSum(degreeHours / 24, span, rateOver(measured));
    }

    /**
     * When the target will be reached at the current rate, or empty when nothing is
     * accumulating — a frozen shed has no completion date.
     */
    public Optional<Instant> forecast(double target, Instant now) {
        if (degreeDays >= target) {
            return Optional.of(now);
        }
        return recentRate
                .filter(rate -> rate >= NEGLIGIBLE_RATE)
                .map(rate -> now.plus(remainingAt(rate, target)));
    }

    /** How long is left at the current rate, for the text next to a progress bar. */
    public Optional<Duration> remaining(double target) {
        if (degreeDays >= target) {
            return Optional.of(Duration.ZERO);
        }
        return recentRate
                .filter(rate -> rate >= NEGLIGIBLE_RATE)
                .map(rate -> remainingAt(rate, target));
    }

    public boolean reached(double target) {
        return degreeDays >= target;
    }

    private Duration remainingAt(double rate, double target) {
        double days = (target - degreeDays) / rate;
        return Duration.ofMinutes(Math.round(days * 24 * 60));
    }

    /*
       Trapezoidal: each pair of readings contributes the average of the two
       temperatures over the time between them. Both are clamped at zero first, so
       a night below freezing adds nothing while the warm hours around it still
       count for what they were.

       Gaps are interpolated rather than skipped. A shed's temperature moves slowly,
       so the average of the readings either side of a two-hour outage is a better
       guess than pretending those two hours did not happen — and pretending would
       under-count, which would mean hanging longer than the meat needs.
    */
    private static double contribution(HistoryPoint earlier, HistoryPoint later) {
        double average = (Math.max(0, earlier.temperature()) + Math.max(0, later.temperature())) / 2;
        double hours = Duration.between(earlier.at(), later.at()).toMillis() / 3_600_000.0;
        return hours <= 0 ? 0 : average * hours;
    }

    /*
       The rate is just the recent average temperature: degree-days per day and
       degrees Celsius are the same number. Averaged over time rather than over
       samples, so a burst of readings does not outweigh a quiet hour.
    */
    private static Optional<Double> rateOver(List<HistoryPoint> measured) {
        Instant from = measured.getLast().at().minus(RATE_WINDOW);
        double degreeHours = 0;
        double hours = 0;
        for (int i = measured.size() - 2; i >= 0; i--) {
            HistoryPoint earlier = measured.get(i);
            HistoryPoint later = measured.get(i + 1);
            degreeHours += contribution(earlier, later);
            hours += Duration.between(earlier.at(), later.at()).toMillis() / 3_600_000.0;
            if (earlier.at().isBefore(from)) {
                break;
            }
        }
        return hours <= 0 ? Optional.empty() : Optional.of(degreeHours / hours);
    }
}
