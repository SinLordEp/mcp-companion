package refac;

/** mc_move e2e: moved to another package; used from Java (OrderService) and Kotlin (OrderReport.kt). */
public class Order {
    private final int quantity;

    public Order(int quantity) {
        this.quantity = quantity;
    }

    public int quantity() {
        return quantity;
    }
}
