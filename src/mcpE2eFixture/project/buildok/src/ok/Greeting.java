package ok;

/** mc_build and mc_diagnostics fixture: no errors; buildbad depends on this module. */
public class Greeting {
    public static String hello(String name) {
        return "Hello, " + name;
    }
}
