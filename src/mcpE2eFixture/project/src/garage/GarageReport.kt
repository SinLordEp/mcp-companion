package garage

import hier.Car

/** mc_pull_up e2e: Kotlin code that uses Car's members. */
fun report(car: Car): String = car.describe() + ", seats: " + Car.SEATS
