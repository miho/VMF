package eu.mihosoft.vmf.conformance.externalbinding.host;

import java.util.Objects;

public final class Distance {
    private final long millimetres;
    private final String note;
    private final boolean estimated;

    public Distance(long millimetres, String note, boolean estimated) {
        this.millimetres = millimetres;
        this.note = note;
        this.estimated = estimated;
    }

    public long millimetres() { return millimetres; }
    public String note() { return note; }
    public boolean estimated() { return estimated; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof Distance)) return false;
        var value = (Distance) other;
        return millimetres == value.millimetres && estimated == value.estimated && note.equals(value.note);
    }

    @Override public int hashCode() {
        return Objects.hash(millimetres, note, estimated);
    }
}
