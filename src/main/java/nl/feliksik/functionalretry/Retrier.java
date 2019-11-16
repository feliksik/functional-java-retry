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

@Value.Immutable
@ImmutableStyles.Entity
public interface Retrier<E, R> {
    int maxAttempts();

    Function<Integer, Duration> backoffFunction();

    Predicate<E> failureMustBeRetried();

    Handlers<E, R> handlers();

    default Either<E, R> executeWithRetries(Supplier<Either<E, R>> retriable) {
        return Stream.range(1, Integer.MAX_VALUE)
                .map(attemptNr -> Attempt.of(attemptNr, retriable.get()))

                // backoff and call handlers
                .peek(attempt -> {
                    if (attempt.isSuccess()) {
                        this.notifySuccessHandlers(attempt);
                    } else {
                        this.notifyFailureHandlersAndSleep(attempt);
                    }
                })
                .dropWhile(this::isFailureAndMustBeRetried)
                .map(Attempt::result)
                .head();
    }

    private boolean isFailureAndMustBeRetried(Attempt<E, R> r) {
        if (r.isSuccess()) {
            return false;
        } else {
            if (this.attemptsExhausted(r)) {
                return false;
            }
            return failureMustBeRetried(r.result().getLeft());
        }
    }

    private boolean attemptsExhausted(Attempt<E, R> a) {
        return a.attemptNr() >= this.maxAttempts();
    }

    private boolean failureMustBeRetried(E e) {
        return this.failureMustBeRetried().test(e);
    }

    private void notifySuccessHandlers(Attempt<E, R> success) {
        if (success.result().isLeft()) {
            throw new IllegalStateException("should be a 'right'");
        }
        this.handlers().onSuccess().ifPresent(handler -> handler.accept(success));
    }

    private void notifyFailureHandlersAndSleep(Attempt<E, R> failure) {
        if (failure.result().isRight()) {
            throw new IllegalStateException("should be a 'left'");
        }

        if (failure.attemptNr() >= this.maxAttempts()) {
            this.handlers().onMaxAttemptsReached().ifPresent(handler -> handler.accept(failure));
        } else if (this.failureMustBeRetried().test(failure.result().getLeft())) {
            this.handlers().onRetriableError().ifPresent(handler -> handler.accept(failure));
            sleepOrThrowInterrupt(this.backoffFunction().apply(failure.attemptNr()));
        } else {
            this.handlers().onNonRetriableError().ifPresent(handler -> handler.accept(failure));
        }
    }

    private void sleepOrThrowInterrupt(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry backoff", interrupted);
        }
    }

    @Value.Immutable
    @ImmutableStyles.Entity
    interface Handlers<E, R> {
        /**
         * handler for event that is retriable. The Event will contain a failure.
         */
        Optional<Consumer<Attempt<E, R>>> onRetriableError();

        /**
         * handler for event that is not retriable, as defined by the {@link Retrier#failureMustBeRetried()} method.
         * The Event will contain a failure.
         */
        Optional<Consumer<Attempt<E, R>>> onNonRetriableError();

        /**
         * handler for event that indicates the max number of retries is reached. The Event will contain a failure.
         */
        Optional<Consumer<Attempt<E, R>>> onMaxAttemptsReached();

        /**
         * handler for a success result.
         */
        Optional<Consumer<Attempt<E, R>>> onSuccess();
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

}
