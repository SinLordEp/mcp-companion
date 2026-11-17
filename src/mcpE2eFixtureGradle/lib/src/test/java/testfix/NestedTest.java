package testfix;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

/** mc_run tests fixture: two nested suites with a test of the same name; only the second one fails. */
@RunWith(Enclosed.class)
public class NestedTest {

    public static class Small {
        @Test
        public void check() {
            assertTrue(1 < 2);
        }
    }

    public static class Large {
        @Test
        public void check() {
            assertTrue("1000 is not below 100", 1000 < 100);
        }
    }
}
