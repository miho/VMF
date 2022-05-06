package eu.mihosoft.vmftest.complex.devcom;

public class Device {
    /**
     * Connection state.
     */
    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        ERROR
    }
}
