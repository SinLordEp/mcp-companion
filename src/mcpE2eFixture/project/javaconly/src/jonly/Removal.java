package jonly;

/**
 * Boundary of mc_diagnostics: javac warns that Integer(int) is deprecated and marked for removal; the IDE's analysis
 * in the sandbox reports it as a plain deprecation warning (its MarkedForRemoval inspection, level ERROR, did not
 * fire here). Seen end to end, docs/notes-step7.md.
 */
public class Removal {
    public Integer boxed() {
        return new Integer(5);
    }
}
