package runfix;

/** Fixture for mc_run: starts, reports a fatal error after a second and exits with code 3. */
public class Crash {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Crash starting");
        Thread.sleep(1000);
        System.err.println("FATAL: boom");
        System.exit(3);
    }
}
