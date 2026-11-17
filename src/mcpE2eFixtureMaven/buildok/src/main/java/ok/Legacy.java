package ok;

/**
 * mc_build and mc_diagnostics fixture: warnings only. The IDE warns about the call of a deprecated method; javac (and
 * the IDE) about the redundant strictfp.
 */
public class Legacy {
    public String greet() {
        return OldApi.old("legacy");
    }

    public strictfp double half(double value) {
        return value / 2;
    }
}
