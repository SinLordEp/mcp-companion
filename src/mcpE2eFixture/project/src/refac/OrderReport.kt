package refac

fun report(order: Order, pricing: Pricing): String = "total " + pricing.total(order.quantity(), "kg")
