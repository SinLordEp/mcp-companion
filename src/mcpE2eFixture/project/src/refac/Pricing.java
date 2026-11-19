package refac;

/** mc_change_signature e2e: total(qty, unit) is called from Java (OrderService) and Kotlin (OrderReport.kt). */
public class Pricing {
    public int total(int qty, String unit) {
        return unit.isEmpty() ? 0 : qty * 2;
    }
}
