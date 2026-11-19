package extract;

/**
 * mc_extract_method e2e: the sum and its loop in total() (lines 11-14) extract into a method returning sum; the two
 * ifs in firstLarge() (lines 21-22) have several exits and cannot be extracted.
 */
public class Invoice {
    private final int minimum = 0;

    public int total(int[] amounts, int discount) {
        int sum = 0;
        for (int amount : amounts) {
            sum += amount;
        }
        int net = sum - discount;
        return net > minimum ? net : minimum;
    }

    public int firstLarge(int[] amounts) {
        for (int i = 0; i < amounts.length; i++) {
            if (amounts[i] > 100) return i;
            if (amounts[i] < 0) break;
        }
        return -1;
    }

    private int count(int[] values) {
        return values.length;
    }
}
