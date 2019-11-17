package nl.feliksik.functionalretry;

import org.immutables.value.Value;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * https://immutables.github.io/style.html
 */
public interface ImmutableStyles {

    @Target({ElementType.PACKAGE, ElementType.TYPE})
    @Retention(RetentionPolicy.CLASS)
    @Value.Style(
            stagedBuilder = false,
            strictBuilder = true,
            overshadowImplementation = true
    )
    @interface Simple {
    }

}
