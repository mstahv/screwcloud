package org.vaadin.example.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.card.CardVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Style;

import com.vaadin.flow.component.AttachEvent;

import in.virit.TemperatureGauge;

import org.vaadin.example.history.HistoryPoint;
import org.vaadin.example.names.SensorNames;
import org.vaadin.example.ruuvi.RuuviReading;
import org.vaadin.firitin.util.style.VaadinCssProps;

/**
 * One tag's card: the temperature on a gauge, the humidity and the age under it,
 * and the last day as a curve.
 *
 * <p>Built to match the server's sensor card, from the same gauge and the same
 * sparkline, because the two are looked at by the same person about the same
 * sensor. What is missing here is what this application does not have: no
 * thresholds colouring the gauge, no alert settings, no degree-day counters.
 *
 * <p>A card is created once per tag and updated through {@link #update}. Rebuilding
 * it every five seconds would make Vaadin resend the whole structure; swapping a
 * number is a fraction of that.
 *
 * <p>Colours come from the {@code --vaadin-*} tokens, which every theme defines.
 * The {@code --lumo-*} ones do not exist in Aura, so a style naming them silently
 * does nothing.
 */
class SensorCard extends Card {

    /** Past this, the reading is still shown but its age is marked. */
    private static final Duration QUIET = Duration.ofMinutes(1);

    private final TemperatureGauge gauge = new DarkGauge();

    /*
       Only shown when there is no temperature. The gauge renders the value with
       its unit, so a text copy of a reading that exists would be the same number
       twice.
    */
    private final Reading noTemperature = new Reading();
    private final Reading humidity = new Reading();
    private final Reading age = new Reading();

    /*
       The same badge the server marks an offline device with, rather than a colour
       of my own: there is no theme agnostic token for "this is wrong", and the
       --lumo-* ones do not exist in Aura, so a style naming them would silently do
       nothing and the warning would simply not appear.
    */
    private final Badge quiet = new Badge();
    private final TemperatureSparkLine sparkLine = new TemperatureSparkLine();

    private final String sensorId;
    private final SensorNames names;
    private final Runnable onRenamed;

    SensorCard(String sensorId, SensorNames names, Runnable onRenamed) {
        this.sensorId = sensorId;
        this.names = names;
        this.onRenamed = onRenamed;

        addThemeVariants(CardVariant.OUTLINED, CardVariant.COVER_MEDIA);
        setMaxWidth("22rem");
        setWidthFull();
        applyName();

        Button rename = new Button(VaadinIcon.PENCIL.create(), click -> renameDialog().open());
        rename.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        rename.setAriaLabel("Rename this sensor");
        setHeaderSuffix(rename);

        /*
           The gauge goes in the media slot, which is what that slot is for: a
           visual belonging to the card rather than part of its text. It has no
           HasSize, so the width goes through the style.
        */
        gauge.getStyle().setWidth("100%");
        setMedia(gauge);

        noTemperature.setText(Readings.MISSING + " °C");

        quiet.addThemeVariants(BadgeVariant.ERROR);
        quiet.setVisible(false);

        add(noTemperature, humidity, age, quiet, sparkLine);
    }

    void update(RuuviReading reading, List<HistoryPoint> history, Instant now) {
        /*
           A gauge showing zero would be indistinguishable from a real zero, so it
           is hidden rather than zeroed when there is no reading, and the dash takes
           its place — a card with neither would look like a rendering fault.
        */
        boolean hasTemperature = reading.temperature() != null;
        gauge.setVisible(hasTemperature);
        noTemperature.setVisible(!hasTemperature);
        if (hasTemperature) {
            gauge.setTemperature(reading.temperature());
        }

        humidity.setText(Readings.format(reading.humidity(), "%.1f %% RH"));

        /*
           A tag that has gone quiet keeps its reading — an hour old is still the
           last thing known about that room — but it must not look like it was
           measured a moment ago.
        */
        boolean stale = reading.isOlderThan(QUIET, now);
        String since = ageText(reading, now);
        age.setVisible(!stale);
        age.setText(since);
        quiet.setVisible(stale);
        quiet.setText("Nothing heard for " + since.replace(" ago", ""));

        sparkLine.setHistory(history);
    }

    /**
     * The title is the name if one was given, otherwise the identifier.
     *
     * <p>When a name takes the title, the identifier moves to the subtitle rather
     * than disappearing: it is what this sensor is called on the server and in the
     * packets, so it is the word that connects what is on this screen to what is
     * on that one. Without a name the two would be the same string twice.
     */
    private void applyName() {
        String name = names.displayName(sensorId);
        setTitle(name);
        setSubtitle(name.equals(sensorId) ? null : new Span(sensorId));
    }

    private Dialog renameDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Name for " + sensorId);

        TextField field = new TextField();
        field.setPlaceholder("Cold room");
        field.setValue(names.nameFor(sensorId).orElse(""));
        field.setWidthFull();

        dialog.add(new VerticalLayout(field));
        dialog.getFooter().add(
                new Button("Cancel", click -> dialog.close()),
                new Button("Save", click -> {
                    names.rename(sensorId, field.getValue());
                    applyName();
                    dialog.close();
                    onRenamed.run();
                }));
        return dialog;
    }

    private static String ageText(RuuviReading reading, Instant now) {
        Duration since = Duration.between(reading.receivedAt(), now);
        if (since.toSeconds() < 60) {
            return "just now";
        }
        if (since.toMinutes() < 60) {
            return since.toMinutes() + " min ago";
        }
        if (since.toHours() < 24) {
            return since.toHours() + " h ago";
        }
        return since.toDays() + " d ago";
    }

    /**
     * The gauge draws itself on a white background whatever the theme, which on a
     * dark page is a bright rectangle in the middle of a card. Painting its first
     * child after render is the workaround; the server module carries the same one,
     * with the same colour and the same reservation — it belongs in the component
     * rather than here.
     *
     * <p>The timeout is what makes it work at all: the element paints itself
     * asynchronously and there is nothing to colour until it has.
     */
    private static class DarkGauge extends TemperatureGauge {
        @Override
        protected void onAttach(AttachEvent event) {
            super.onAttach(event);
            getElement().executeJs(
                    "var el = this; setTimeout(() => {"
                    + "el.firstChild.style.background = 'rgb(40 44 52)';}, 100);");
        }
    }

    /**
     * One line of secondary text on the card. Block display because these are spans
     * and each belongs on its own line; the size is the theme's.
     */
    private static class Reading extends Span {
        Reading() {
            getStyle().setColor(VaadinCssProps.TEXT_COLOR_SECONDARY.var());
            getStyle().setDisplay(Style.Display.BLOCK);
        }
    }
}
