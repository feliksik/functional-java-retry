package nl.feliksik.functionalretry;

import io.vavr.collection.List;
import io.vavr.collection.Stream;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.function.Function;

public class BackoffTest {

    @Test
    public void testExponentialBackoff() {
        Function<Integer, Duration> sut = Backoff.cappedExponential(Duration.ofMillis(10), 2.0, Duration.ofMillis(3_000));

        List<Integer> backoffs = Stream.range(1, 14)
                .map(attemptNr -> (int) sut.apply(attemptNr).toMillis())
                .toList();
        List<Integer> expected = List.of(10, 20, 40, 80, 160, 320, 640, 1280, 2560, 3000, 3000, 3000, 3000);
        Assert.assertEquals(expected, backoffs);
    }
}
