package slowfix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** mc_run tests fixture: six tests of 1.5 s each (about 9 s), to look at results while the run is in progress. */
public class SlowTest {

    private static void pause() throws InterruptedException {
        Thread.sleep(1500);
    }

    @Test
    public void step1() throws InterruptedException {
        pause();
    }

    @Test
    public void step2() throws InterruptedException {
        pause();
    }

    @Test
    public void step3() throws InterruptedException {
        pause();
        assertEquals("three", "drei");
    }

    @Test
    public void step4() throws InterruptedException {
        pause();
    }

    @Test
    public void step5() throws InterruptedException {
        pause();
    }

    @Test
    public void step6() throws InterruptedException {
        pause();
    }
}
