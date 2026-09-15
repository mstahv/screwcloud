package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.UnorderedList;

/**
 * How to get a built file onto a Pico, for somebody who has never done it.
 *
 * <p>Its own class because it is needed in two places — after a build, and again
 * whenever somebody re-flashes a device they already have — and because it is the
 * part of this feature most likely to be read by a person who does not want to
 * know anything about any of it.
 *
 * <p>Shown <b>after</b> the build rather than beside the form. These six steps
 * are the ones that matter at the moment the file exists, and put next to the
 * form they would be six paragraphs of unfinished business between a reader and
 * the button they came to press.
 *
 * <p>The drive is called {@code RP2350} on a Pico 2. The older board's is
 * {@code RPI-RP2}, and confusing the two is exactly what makes a person think
 * nothing happened — so the name is given, and step 6 says in advance that the
 * warning Finder shows is normal, because that is the other moment somebody
 * decides they have broken something.
 *
 * <p>Afterwards it describes the <b>light</b> rather than the display. A display
 * is an optional extra that most of these devices will not have, so telling a
 * recipient what its bottom row says is telling most of them nothing. The light
 * on the Pico is on every board, and the three rhythms it has are exactly the
 * three things somebody standing next to a new device wants to know. They are
 * {@code BLINK_STEPS_*} in the firmware, where the numbers are milliseconds
 * alternating on and off.
 */
class FlashingInstructions extends Section {

    FlashingInstructions() {
        OrderedList steps = new OrderedList(
                new ListItem("Unplug the USB cable from the device, if it is plugged in."),
                new ListItem("Find the single small button on the Pico board, marked BOOTSEL. "
                        + "Press and hold it."),
                new ListItem("While still holding it, plug the USB cable into your computer."),
                new ListItem("Let go of the button. A drive called RP2350 appears, "
                        + "like a memory stick."),
                new ListItem("Copy the file you just downloaded onto that drive."),
                new ListItem("The drive disappears by itself after a second or two and the "
                        + "device starts up. On a Mac you may get a warning that a disk was "
                        + "not ejected properly — that is normal here, and nothing is wrong."));

        /*
           The light, not the display. A display is an optional extra that most
           of these devices will not have, and the small light on the Pico itself
           is the one thing every one of them can say anything with — which makes
           it the only status a recipient is certain to be able to read.
        */
        UnorderedList light = new UnorderedList(
                new ListItem("Two quick blinks, then a pause — it is sending. All well."),
                new ListItem("A slow, steady blink — nothing sent yet. Normal for the first "
                        + "minute or so after starting."),
                new ListItem("Fast flickering — it cannot send. Usually the WiFi name or "
                        + "password; build again and check them."));

        add(new SectionHeading("Getting it onto the device"), steps,
                new SectionHeading("What the light tells you"),
                new Hint("The small light on the Pico board itself, once it has started up."),
                light);
    }
}
