package app.shoalwatch.security

import org.junit.Assert.assertEquals
import org.junit.Test

/** Milestone 9: the phone and the car screen share one "in front"; the gate hears only the first return and the last leave. */
class AppFrontTest {
    private val calls = mutableListOf<String>()
    private val front = AppFront(object : AppTrips {
        override fun onLeft() { calls += "left" }
        override fun onReturned() { calls += "returned" }
    })
    private val phone = front.screen(Screen.PHONE)
    private val car = front.screen(Screen.CAR)

    @Test fun thePhoneAloneIsToldAsBefore() {
        phone.onReturned(); phone.onLeft(); phone.onReturned()
        assertEquals(listOf("returned", "left", "returned"), calls)
    }

    @Test fun theCarOpeningWhileThePhoneIsAwayIsAReturnAndItsLeavingIsALeave() {
        phone.onReturned(); phone.onLeft()
        car.onReturned() // after "Lock after" this is what locks
        car.onLeft()
        assertEquals(listOf("returned", "left", "returned", "left"), calls)
    }

    @Test fun whileTheCarShowsTheAppThePhoneComingAndGoingIsNoTrip() {
        car.onReturned()
        phone.onReturned(); phone.onLeft(); phone.onReturned(); phone.onLeft()
        assertEquals("the app never left", listOf("returned"), calls)
        car.onLeft()
        assertEquals(listOf("returned", "left"), calls)
    }

    @Test fun thePhoneLeavingWhileTheCarShowsTheAppStartsNoClockUntilTheCarGoesToo() {
        phone.onReturned(); car.onReturned(); phone.onLeft()
        assertEquals(listOf("returned"), calls)
        car.onLeft()
        assertEquals(listOf("returned", "left"), calls)
    }

    @Test fun aRepeatedLeaveOrReturnCountsOnce() {
        phone.onReturned(); phone.onReturned()
        phone.onLeft(); phone.onLeft() // picture-in-picture, then the activity's stop
        car.onLeft() // a car screen that was never shown
        assertEquals(listOf("returned", "left"), calls)
    }
}
