package abstractfix;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** mc_run start_at fixture: a test in an abstract class; the IDE asks which subclass to run. */
public abstract class ShapeContractTest {

    protected abstract double area();

    @Test
    public void hasArea() {
        assertTrue(area() > 0);
    }
}
