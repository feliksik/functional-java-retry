package nl.feliksik.functionalretry;

import io.vavr.collection.Stream;
import io.vavr.control.Either;
import org.immutables.value.Value;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
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

        Consumer<Attempt<E, R>> performUserDefinedSideEffectsAndSleep = attempt -> this.dispatchToHandlerScenario(attempt, getSideEffectHandler());
        Predicate<Attempt<E, R>> resultMustBeRetried = attempt -> this.dispatchToHandlerScenario(attempt, trueIfAttemptMustBeRetried());

        return Stream.range(1, Integer.MAX_VALUE)
                .map(attemptNr -> Attempt.of(attemptNr, retriable.get()))
                .peek(performUserDefinedSideEffectsAndSleep)
                .dropWhile(resultMustBeRetried)
                .map(Attempt::result)
                .head();
    }


    private interface MutuallyExclusiveScenarioHandler<E, R, T> {

        T handleSuccess(Attempt<E, R> a);

        T handleRetriableError(Attempt<E, R> a);

        T handleNonRetriableError(Attempt<E, R> a);

        T handleAttemptsExhausted(Attempt<E, R> a);
    }

    private MutuallyExclusiveScenarioHandler<E, R, Boolean> trueIfAttemptMustBeRetried() {
// returns true if a result must be retried, false if it can be returned to the user
        final Boolean ATTEMPT_MUST_BE_RETRIED = Boolean.TRUE;
        final Boolean RETURN_TO_USER = Boolean.FALSE;
        return new MutuallyExclusiveScenarioHandler<E, R, Boolean>() {
            @Override
            public Boolean handleSuccess(Attempt a) {
                return RETURN_TO_USER;
            }

            @Override
            public Boolean handleRetriableError(Attempt a) {
                return ATTEMPT_MUST_BE_RETRIED;
            }

            @Override
            public Boolean handleNonRetriableError(Attempt a) {
                return RETURN_TO_USER;
            }

            @Override
            public Boolean handleAttemptsExhausted(Attempt a) {
                return RETURN_TO_USER;
            }
        };
    }

    private MutuallyExclusiveScenarioHandler<E, R, Void> getSideEffectHandler() {
        return new MutuallyExclusiveScenarioHandler<E, R, Void>() {
            @Override
            public Void handleSuccess(Attempt a) {
                config.handlers().ifPresent(h -> h.onSuccess(a));
                return null;
            }

            @Override
            public Void handleRetriableError(Attempt a) {
                config.handlers().ifPresent(h -> h.onRetriableError(a));
                sleepOrThrowInterrupt(config.backoffFunction().apply(a.attemptNr()));
                return null;
            }

            @Override
            public Void handleNonRetriableError(Attempt a) {
                config.handlers().ifPresent(h -> h.onNonRetriableError(a));
                return null;
            }

            @Override
            public Void handleAttemptsExhausted(Attempt a) {
                config.handlers().ifPresent(h -> h.onMaxAttemptsReached(a));
                return null;
            }

            private void sleepOrThrowInterrupt(Duration d) {
                try {
                    Thread.sleep(d.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", interrupted);
                }
            }
        };
    }

    private <T> T dispatchToHandlerScenario(Attempt<E, R> attempt, MutuallyExclusiveScenarioHandler<E, R, T> handler) {
        if (attempt.isSuccess()) {
            return handler.handleSuccess(attempt);
        } else if (failureIsNotRetriable(attempt.result().getLeft())) {
            return handler.handleNonRetriableError(attempt);
        } else if (attempt.attemptNr() >= this.config.maxAttempts()) {
            return handler.handleAttemptsExhausted(attempt);
        } else {
            return handler.handleRetriableError(attempt);
        }
    }

    private boolean failureIsNotRetriable(E failure) {
        return !this.config.retryFailureIf().test(failure);
    }


    @Value.Immutable
    @ImmutableStyles.Entity
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
