package eu.mihosoft.vmf.portable.numbers.vmfmodel;

// Scalar types beyond boolean, int and String.
interface Numbers {
    long getBig();

    double getRatio();

    Double getOptionalRatio();

    Long[] getBigs();

    Double[] getRatios();
}
