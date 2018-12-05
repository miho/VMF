package eu.mihosoft.vmf.runtime.core;

import java.util.Objects;

public interface Annotation {
    String getKey();
    String getValue();

    default boolean equals(String key, String value) {
        return Objects.equals(getKey(), key) && Objects.equals(value, getValue());
    }
}
