package nl.feliksik.functionalretry;

import java.time.Duration;
import java.util.function.Function;

public class Backoff {
    public static Function<Integer, Duration> cappedExponential(Duration initial, double powerBase, Duration maxDuration) {
        long maxMillis = maxDuration.toMillis();
        return attemptNr -> {
            long exponentialMillis = (long) (((double) initial.toMillis()) * Math.pow(powerBase, attemptNr - 1));
            long cappedMillis = Math.min(exponentialMillis, maxMillis);
            return Duration.ofMillis(cappedMillis);
        };
    }

    public static Function<Integer, Duration> cappedExponential(Duration initial, Duration maxDuration) {
        return cappedExponential(initial, 2.0, maxDuration);
    }
}
