package history;

/** A ledger whose history the e2e Git repository records (see GitFixture in buildSrc). */
public class Ledger {
    private int balance;

    public void deposit(int amount) {
        balance += amount;
    }

    public int balance() {
        return balance;
    }
}
