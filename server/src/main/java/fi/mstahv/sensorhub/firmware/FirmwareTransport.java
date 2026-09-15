package fi.mstahv.sensorhub.firmware;

/**
 * Which radio the built firmware reports over, and the {@code #define} in
 * config.h that says so.
 *
 * <p>{@link #AUTOMATIC} is the device's own default: it probes for the NB-IoT
 * modem at boot and falls back to WiFi if nothing answers. It is the right
 * choice for almost everybody, and the other two exist for the reader who knows
 * their device has only one radio and does not want to spend two seconds of
 * every boot proving it.
 */
public enum FirmwareTransport {

    AUTOMATIC("TRANSPORT_AUTO", "Detect at boot"),
    WIFI("TRANSPORT_WIFI", "WiFi only"),
    NBIOT("TRANSPORT_NBIOT", "NB-IoT only");

    private final String macro;
    private final String caption;

    FirmwareTransport(String macro, String caption) {
        this.macro = macro;
        this.caption = caption;
    }

    /** The config.h macro this choice defines, the other two being commented out. */
    public String macro() {
        return macro;
    }

    /** What the form calls it. */
    public String caption() {
        return caption;
    }
}
