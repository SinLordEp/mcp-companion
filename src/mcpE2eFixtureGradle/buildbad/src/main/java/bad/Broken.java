package bad;

import ok.Greeting;

/**
 * mc_build and mc_diagnostics fixture: a compile error in a module that depends on buildok. Building buildok (with
 * what it depends on) succeeds; building buildbad, buildok with its dependents, or the whole project fails.
 */
public class Broken {
    public String greet() {
        return Greeting.missing("bad");
    }
}
