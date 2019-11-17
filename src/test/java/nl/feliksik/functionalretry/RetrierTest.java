package nl.feliksik.functionalretry;


import io.vavr.collection.List;
import io.vavr.control.Either;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class RetrierTest {
    enum RetrierScenario {SUCCESS, RETRIABLE, FAIL_PERMANENT, MAX_RETRIED}

    private final Either<AppException, String> RESULT_SUCCESS = Either.right("OK");
    private final Either<AppException, String> RESULT_FAIL_RETRIABLE = Either.left(new AppException(true));
    private final Either<AppException, String> RESULT_FAIL_PERMANENTLY = Either.left(new AppException(false));

    private TestEventHandler testEventHandler = new TestEventHandler();

    @Test
    public void exampleOfHowToWrapExceptions() {
        ServiceThatThrows serviceThatThrows = new ServiceThatThrows();

        Retrier<AppException, String> retrier = Retrier
                .withConfig(
                        RetryConfig.<AppException, String>builder()
                                .maxAttempts(3)
                                .backoffFunction(Backoff.cappedExponential(Duration.ofMillis(1), 2.0, Duration.ofMillis(8)))
                                .retryFailureIf(e -> e.isRetriable)
                                .handlers(testEventHandler)
                                .build()
                );

        // execute SUT
        Either<AppException, String> finalResult = retrier.executeWithRetries(() -> {
                    try {
                        return Either.right(serviceThatThrows.executeCall("my input"));
                    } catch (AppException e) {
                        return Either.left(e);
                    }
                }
        );

        assertTrue(finalResult.isLeft());
        assertEquals(3, testEventHandler.getActualEventsReceived().length());
        assertEquals(RetrierScenario.MAX_RETRIED, testEventHandler.lastEventType);
    }


    @Test
    public void succesOnFirstAttempt() {
        List<Either<AppException, String>> serviceResponses = List.of(RESULT_SUCCESS);
        Supplier<Either<AppException, String>> service = createServiceThatReturns(serviceResponses);

        Retrier<AppException, String> retrier = Retrier
                .withConfig(
                        RetryConfig.<AppException, String>builder()
                                .maxAttempts(3)
                                .backoffFunction(attemptNr -> Duration.ofMillis(1))
                                .retryFailureIf(e -> e.isRetriable)
                                .handlers(testEventHandler)
                                .build()
                );

        // execute SUT
        Either<AppException, String> finalResult = retrier.executeWithRetries(service);

        assertTrue(finalResult.isRight());
        assertEquals(
                constructExpectedEvents(serviceResponses),
                testEventHandler.getActualEventsReceived()
        );
        assertEquals(RetrierScenario.SUCCESS, testEventHandler.lastEventType);
    }

    @Test
    public void retryMaxAttempts() {
        List<Either<AppException, String>> serviceResponses = List.of(
                RESULT_FAIL_RETRIABLE,
                RESULT_FAIL_RETRIABLE,
                RESULT_FAIL_RETRIABLE,
                RESULT_FAIL_RETRIABLE,
                RESULT_FAIL_RETRIABLE
        );

        Supplier<Either<AppException, String>> service = createServiceThatReturns(serviceResponses);

        Retrier<AppException, String> retrier = Retrier
                .withConfig(
                        RetryConfig.<AppException, String>builder()
                                .maxAttempts(3)
                                .backoffFunction(attemptNr -> Duration.ofMillis(1))
                                .retryFailureIf(e -> e.isRetriable)
                                .handlers(testEventHandler)
                                .build()
                );

        Either<AppException, String> finalResult = retrier.executeWithRetries(service);

        assertTrue(finalResult.isLeft());
        assertEquals(
                constructExpectedEvents(serviceResponses.take(3)),
                testEventHandler.getActualEventsReceived()
        );
        assertEquals(RetrierScenario.MAX_RETRIED, testEventHandler.lastEventType);
    }

    @Test
    public void successAfterRetries() {
        List<Either<AppException, String>> serviceResponses = List.of(
                RESULT_FAIL_RETRIABLE,
                RESULT_FAIL_RETRIABLE,
                RESULT_SUCCESS,
                RESULT_FAIL_PERMANENTLY);

        Supplier<Either<AppException, String>> service = createServiceThatReturns(serviceResponses);

        Retrier<AppException, String> retrier = Retrier
                .withConfig(
                        RetryConfig.<AppException, String>builder()
                                .maxAttempts(3)
                                .backoffFunction(attemptNr -> Duration.ofMillis(1))
                                .retryFailureIf(e -> e.isRetriable)
                                .handlers(testEventHandler)
                                .build()
                );

        Either<AppException, String> finalResult = retrier.executeWithRetries(service);

        assertTrue(finalResult.isRight());
        assertEquals(
                constructExpectedEvents(serviceResponses.take(3)),
                testEventHandler.getActualEventsReceived()
        );
        assertEquals(RetrierScenario.SUCCESS, testEventHandler.lastEventType);
    }

    @Test
    public void failPermanent() {
        List<Either<AppException, String>> serviceResponses = List.of(
                RESULT_FAIL_RETRIABLE,
                RESULT_FAIL_RETRIABLE,
                RESULT_FAIL_PERMANENTLY);

        Supplier<Either<AppException, String>> service = createServiceThatReturns(serviceResponses);

        Retrier<AppException, String> retrier = Retrier
                .withConfig(
                        RetryConfig.<AppException, String>builder()
                                .maxAttempts(3)
                                .backoffFunction(attemptNr -> Duration.ofMillis(1))
                                .retryFailureIf(e -> e.isRetriable)
                                .handlers(testEventHandler)
                                .build()
                );

        Either<AppException, String> finalResult = retrier.executeWithRetries(service);

        assertTrue(finalResult.isLeft());
        assertEquals(
                constructExpectedEvents(serviceResponses.take(3)),
                testEventHandler.getActualEventsReceived()
        );
        assertEquals(RetrierScenario.FAIL_PERMANENT, testEventHandler.lastEventType);
    }

    private Supplier<Either<AppException, String>> createServiceThatReturns(List<Either<AppException, String>> results) {
        return results.iterator()::next;
    }

    private class TestEventHandler implements Retrier.Handlers<AppException, String> {
        private java.util.List<Retrier.Attempt<AppException, String>> actualEventsReceived = new ArrayList<>();

        private RetrierScenario lastEventType = null;
        private int previousAttemptNr = 0;

        @Override
        public void onRetriableError(Retrier.Attempt<AppException, String> attempt) {
            actualEventsReceived.add(attempt);
            assertSequencesAreCorrect(attempt);
            this.lastEventType = RetrierScenario.RETRIABLE;
        }

        @Override
        public void onNonRetriableError(Retrier.Attempt<AppException, String> attempt) {
            actualEventsReceived.add(attempt);
            assertSequencesAreCorrect(attempt);
            this.lastEventType = RetrierScenario.FAIL_PERMANENT;

        }

        @Override
        public void onMaxAttemptsReached(Retrier.Attempt<AppException, String> attempt) {
            actualEventsReceived.add(attempt);
            assertSequencesAreCorrect(attempt);
            this.lastEventType = RetrierScenario.MAX_RETRIED;
        }

        @Override
        public void onSuccess(Retrier.Attempt<AppException, String> attempt) {
            actualEventsReceived.add(attempt);
            assertSequencesAreCorrect(attempt);
            this.lastEventType = RetrierScenario.SUCCESS;
        }

        private void assertSequencesAreCorrect(Retrier.Attempt<AppException, String> attempt) {
            assertEquals(this.previousAttemptNr + 1, attempt.attemptNr());
            this.previousAttemptNr = attempt.attemptNr();

            // assert that previous attempt was a retriable event
            if (attempt.attemptNr() > 1) {
                assertEquals(RetrierScenario.RETRIABLE, lastEventType);
            }
        }

        private List getActualEventsReceived() {
            return actualEventsReceived.stream().collect(List.collector());
        }
    }

    public List<Retrier.Attempt<AppException, String>> constructExpectedEvents(List<Either<AppException, String>> serviceResponses) {
        return serviceResponses
                .zip(io.vavr.collection.Stream.range(1, Integer.MAX_VALUE))
                .map(tuple -> ImmutableAttempt.<AppException, String>builder()
                        .attemptNr(tuple._2)
                        .result(tuple._1)
                        .build()
                )
                .toList();
    }

    public class AppException extends RuntimeException {
        private final boolean isRetriable;

        private AppException(boolean isRetriable) {
            super("Some Exception");
            this.isRetriable = isRetriable;
        }
    }

    public class ServiceThatThrows {
        public String executeCall(String input) throws AppException {
            throw new AppException(true);
        }
    }
}