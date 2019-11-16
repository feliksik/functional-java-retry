package nl.feliksik.functionalretry;

import io.vavr.control.Either;
import org.junit.Test;

import java.time.Duration;
import java.util.function.Supplier;

public class RetrierTest {

    public class ApplicationException extends RuntimeException {
        public ApplicationException(String s) {
            super(s);
        }
    }


    @Test
    public void doRetry() {

        final SomeServiceWithExceptions service = new SomeServiceWithExceptions();

        String input = "some function input";

        // wrap call in functional style, keeping exceptions local and type-specific
        Supplier<Either<ApplicationException, String>> supplierOfResult = () -> {
            try {
                return Either.right(service.callThatMayFail(input));
            } catch (ApplicationException e) {
                return Either.left(e);
            }
        };

        // configure retrier
        Retrier<ApplicationException, String> retrier = ImmutableRetrier.<ApplicationException, String>builder()
                .maxAttempts(3)
                .backoffFunction(attemptNr -> Duration.ofMillis(2))
                .failureMustBeRetried(e -> e.toString().contains("retriable"))
                .handlers(
                        ImmutableHandlers.<ApplicationException, String>builder()
                                .onMaxAttemptsReached(e -> System.out.println("MaxAttemptsReached: " + e))
                                .onNonRetriableError(e -> System.out.println("onNonRetriableError: " + e))
                                .onRetriableError(e -> System.out.println("onRetriableError: " + e))
                                .onSuccess(e -> System.out.println("onSuccess: " + e))
                                .build()
                )
                .build();

        // execute the call via retrier
        final Either<ApplicationException, String> finalResult = retrier.executeWithRetries(supplierOfResult);

        if (finalResult.isRight()) {
            System.out.println("Right! " + finalResult.get());
        } else {
            System.out.println("Left! " + finalResult.getLeft());
        }
    }

    public class SomeServiceWithExceptions {
        int attempts = 0;

        private String callThatMayFail(String s) throws ApplicationException {
            attempts++;
            if (attempts >= 10) {
                if (true) {
                    throw new ApplicationException("RETRY_IMPOSSIBLE");
                }
                return "result of " + s;
            }
            throw new ApplicationException("retriable");
        }
    }

}