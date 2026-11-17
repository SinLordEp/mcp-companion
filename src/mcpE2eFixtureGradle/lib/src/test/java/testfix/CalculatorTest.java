package testfix;

import static org.junit.Assert.assertEquals;

import org.junit.Ignore;
import org.junit.Test;

/** mc_run tests fixture: two passing tests, a failed assertion with expected/actual, an error and an ignored test. */
public class CalculatorTest {

    @Test
    public void adds() {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void multiplies() {
        assertEquals(6, 2 * 3);
    }

    @Test
    public void greets() {
        assertEquals("Hello, world", "Hello, " + "World");
    }

    @Test
    public void divides() {
        int zero = Integer.parseInt("0");
        System.out.println("dividing by " + zero);
        assertEquals(1, 1 / zero);
    }

    @Ignore("not ready")
    @Test
    public void subtracts() {
        assertEquals(0, 1 - 1);
    }
}
