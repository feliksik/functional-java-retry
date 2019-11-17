package nl.feliksik.functionalretry;

import io.vavr.control.Either;
import org.immutables.value.Value;

import java.time.Duration;
import java.util.function.Supplier;

public final class Retrier<E, R> {

    private RetryConfig<E, R> config;

    private Retrier(RetryConfig<E, R> config) {
        this.config = config;
    }

    public static <E, R> Retrier<E, R> withConfig(RetryConfig<E, R> config) {
        return new Retrier<>(config);
    }

    public final Either<E, R> executeWithRetries(Supplier<Either<E, R>> retriable) {
        int attemptNr = 0;
        while (true) {
            attemptNr++;
            Either<E, R> result = retriable.get();
            Attempt<E, R> attempt = Attempt.of(attemptNr, result);

            if (attempt.isSuccess()) {
                notifySuccess(attempt);
                return result;
            } else {
                if (failureIsNotRetriable(result.getLeft())) {
                    notifyNonRetriableError(attempt);
                    return result;
                } else if (attemptNr >= config.maxAttempts()) {
                    notifyAttemptsExhausted(attempt);
                    return result;
                } else {
                    notifyRetriableError(attempt);
                    sleepOrThrowInterrupt(config.backoffFunction().apply(attempt.attemptNr()));
                }
            }
        }
    }

    public void notifySuccess(Attempt a) {
        config.handlers().ifPresent(h -> h.onSuccess(a));
    }

    public void notifyRetriableError(Attempt a) {
        config.handlers().ifPresent(h -> h.onRetriableError(a));
    }

    public void notifyNonRetriableError(Attempt a) {
        config.handlers().ifPresent(h -> h.onNonRetriableError(a));
    }

    public void notifyAttemptsExhausted(Attempt a) {
        config.handlers().ifPresent(h -> h.onMaxAttemptsReached(a));
    }

    private void sleepOrThrowInterrupt(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry backoff", interrupted);
        }
    }

    private boolean failureIsNotRetriable(E failure) {
        return !this.config.retryFailureIf().test(failure);
    }

    @Value.Immutable
    @ImmutableStyles.Simple
    interface Attempt<E, R> {
        int attemptNr();

        Either<E, R> result();

        static <E, R> Attempt<E, R> of(int attemptNr, Either<E, R> result) {
            return ImmutableAttempt.<E, R>builder()
                    .attemptNr(attemptNr)
                    .result(result)
                    .build();
        }

        default boolean isSuccess() {
            return result().isRight();
        }
    }

    interface Handlers<E, R> {
        /**
         * handler for event that is retriable. The Event will contain a failure.
         */
        void onRetriableError(Attempt<E, R> attempt);

        /**
         * handler for event that is not retriable, as defined by the {@link RetryConfig#retryFailureIf()} method.
         * The Event will contain a failure.
         */
        void onNonRetriableError(Attempt<E, R> attempt);

        /**
         * handler for event that indicates the max number of retries is reached. The Event will contain a failure.
         */
        void onMaxAttemptsReached(Attempt<E, R> attempt);

        /**
         * handler for a success result. It only makes sense to use this for logging the attempt number, as the result
         * will be returned by the Retrier after calling this handler.
         */
        void onSuccess(Attempt<E, R> attempt);
    }
}
