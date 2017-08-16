package eu.mihosoft.vmf.core;

/**
 * Created by miho on 25.01.2017.
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Deprecated
public class StringUtil {
    public static String padRight(String s, int n) {
        return String.format("%1$-" + n + "s", s);
    }

    public static String padRight(int s, int n) {
        return String.format("%1$-" + n + "s", s);
    }

    public static String alignRight(String s, int n) {
        return String.format("%" + n + "s", s);
    }

    public static String alignRight(int s, int n) {
        return String.format("%" + n + "s", s);
    }
}
