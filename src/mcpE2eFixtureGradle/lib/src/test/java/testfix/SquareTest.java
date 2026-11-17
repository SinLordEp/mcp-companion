package testfix;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** mc_run tests fixture: one test method run three times with different data; the third run fails. */
@RunWith(Parameterized.class)
public class SquareTest {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{1, 1}, {2, 4}, {3, 10}});
    }

    private final int input;
    private final int expected;

    public SquareTest(int input, int expected) {
        this.input = input;
        this.expected = expected;
    }

    @Test
    public void square() {
        assertEquals(expected, input * input);
    }
}
