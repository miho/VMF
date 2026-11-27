package eu.mihosoft.vmf.portable.externals;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The Java host value of the external type Distance (portable contract module "externals"). Jackson writes it as
 * {"meters": number}, the form the C# and TypeScript codecs share.
 */
public final class Distance {
    private final double meters;

    @JsonCreator
    public Distance(@JsonProperty("meters") double meters) {
        this.meters = meters;
    }

    public double getMeters() {
        return meters;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Distance && Double.compare(meters, ((Distance) other).meters) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(meters);
    }

    @Override
    public String toString() {
        return meters + " m";
    }
}
