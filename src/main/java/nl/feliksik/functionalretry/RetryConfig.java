package nl.feliksik.functionalretry;

import org.immutables.value.Value;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;


@Value.Immutable
@ImmutableStyles.Entity
public interface RetryConfig<E, R> {
    int maxAttempts();

    /**
     * The backoff function determines how long to sleep for the given attempt number.
     * The first failure will have attempt number 1.
     */
    Function<Integer, Duration> backoffFunction();

    /**
     * Predicate that determines whether a failure is retriable.
     */
    Predicate<E> retryFailureIf();

    /**
     * Handler for logging or metrics
     */
    Optional<Retrier.Handlers<E, R>> handlers();

    @Value.Check
    default void validate() {
        if (maxAttempts() <= 0) {
            throw new IllegalStateException("maxAttempts should be >= 1");
        }
    }

    static <E, R> ImmutableRetryConfig.Builder<E, R> builder() {
        return ImmutableRetryConfig.builder();
    }
}
