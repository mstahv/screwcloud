/**
 * The application's own Bean Validation constraints.
 *
 * <p>The rules live here rather than in the entities or the forms because both
 * ends need the same ones. An entity carries them so nothing reaches the database
 * that should not — Hibernate runs them before every insert and update — and the
 * forms read the very same annotations to mark a field required, to refuse a save
 * and to say why. A rule written twice is a rule that will disagree with itself.
 *
 * <p>Two kinds of constraint are used here, and the difference is worth
 * recognising:
 *
 * <ul>
 * <li>{@link fi.mstahv.sensorhub.validation.SensorId} is <i>composed</i>: it has
 * no validator of its own, only other constraints stacked on it. That is enough
 * whenever the rule is a shape a stock constraint can already express.
 * <li>{@link fi.mstahv.sensorhub.validation.DeviceId},
 * {@link fi.mstahv.sensorhub.validation.PushEndpoint} and
 * {@link fi.mstahv.sensorhub.validation.IncreasingBands} have validators of their
 * own, because each needs something no annotation can state: normalising before
 * checking, parsing a URL, and comparing four values with each other.
 * </ul>
 *
 * <p>All of them follow the convention that {@code null} is valid. Whether a value
 * is required is a separate question, answered by {@code @NotNull} or
 * {@code @NotBlank} next to them — which is also what Viritin's
 * {@code FormBinder} looks for when it decides which fields get the required
 * indicator.
 */
package fi.mstahv.sensorhub.validation;
