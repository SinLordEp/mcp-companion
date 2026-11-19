package multifix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** mc_run start_at fixture: a test class that also has a main method, so its class line has two run targets. */
public class RunnableTest {

    public static void main(String[] args) {
        System.out.println("main of RunnableTest");
    }

    @Test
    public void runs() {
        assertEquals(2, 1 + 1);
    }
}
