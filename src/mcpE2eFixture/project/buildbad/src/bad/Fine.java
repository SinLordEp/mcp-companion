package bad;

import ok.Greeting;

/** mc_build and mc_diagnostics fixture: a file without problems next to Broken. */
public class Fine {
    public String greet() {
        return Greeting.hello("fine");
    }
}
