package refac;

public class OrderService {
    private final Pricing pricing = new Pricing();

    public int price(Order order) {
        return pricing.total(order.quantity(), "kg") + pricing.total(1, "g");
    }
}
