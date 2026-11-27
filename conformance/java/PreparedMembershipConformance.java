package eu.mihosoft.vmf.runtime.core.internal;

/** Lets classpath-based conformance use the same optional bridge as the journal recorder. */
public final class PreparedMembershipConformance {
    private PreparedMembershipConformance() { }

    public static boolean available() {
        boolean available = MembershipPreparation.available();
        if (!available && "1".equals(System.getenv("VMF_CONFORMANCE_PREPARED_MEMBERSHIP"))) {
            throw new IllegalStateException(
                    "VMF_CONFORMANCE_PREPARED_MEMBERSHIP=1 requires VMF to link the VCollections map and set preparation hooks");
        }
        return available;
    }
}
