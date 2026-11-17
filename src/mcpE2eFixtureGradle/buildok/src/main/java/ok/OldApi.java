package ok;

/** mc_build and mc_diagnostics fixture: a deprecated method, used by Legacy. */
public class OldApi {
    /** @deprecated use {@link Greeting#hello(String)}. */
    @Deprecated
    public static String old(String name) {
        return Greeting.hello(name);
    }
}
