package fixes;

/**
 * mc_quick_fix fixture: missing imports (Widget: one class; List: java.util.List, the only generic one; Date: two
 * classes), an unhandled exception, a type mismatch and a class that does not exist.
 */
public class Broken {
    private final Widget widget = null;
    private final List<String> names = null;
    private final Date created = null;

    void load() {
        throw new java.io.IOException("missing");
    }

    int size = "three";

    Object make() {
        return new Missing();
    }
}
