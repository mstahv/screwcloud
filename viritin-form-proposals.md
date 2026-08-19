# Making BeanValidationForm comfortable as part of a page

Notes for Viritin, from using `BeanValidationForm` in ScrewCloud. The application
has four bound forms and none of them is a page: one is a section of the front page
(add a device), one fills a settings popover, and two are single rows inside that
popover (the degree-day counters). Every one of them had to undo the same two
assumptions before it could be what it was, and the undoing accumulated into a local
base class — `server/.../ui/RowForm.java` — which is really a sketch of what the
library could provide.

What worked should be said first, because it is why the forms here are short: binding
a record by field name, constraints travelling from the record to the fields, eager
saving with "only when the row is still valid" semantics, and
`setEntityWithEnabledSave` for a form whose initial state is already worth saving.
None of the notes below touch that half.

Written against Viritin 3.8.0.

**Status:** proposals 1, 2 and 4 are implemented on the `form-embedding` branch of
the `./flow-viritin` checkout (one commit on top of master), built locally as
`3.8.1-SNAPSHOT`, and this server module now runs against it: `RowForm` is gone,
the four forms call `asSection()` and carry no empty `getFormComponents()`, and the
ENTER flow was exercised end to end in a browser — including reopening the popover,
which used to stack a new shortcut registration per attach (a real DefaultButton
bug found while wiring this; fixed in the same commit). Proposal 3, the
`BoundForm` split, is deliberately left as a sketch: it is a larger reshaping of a
class marked experimental, and the first three remove most of its practical
motivation.

---

## 1. The Composite is size-full by default

`BeanValidationForm` extends `Composite<Div>`, and that `Div` is size-full. For a
form that is the page, fine. For every form in this application it was a bug with
two different symptoms, each discovered separately:

- As a **section of a page**, a full-height form pushes everything below it off the
  screen.
- Inside a **popover** — which sizes itself to its content — a full-height child is
  a child with no height at all.

Every embedded form here carried the same correction, with the same explanatory
comment, until they were collected into a base class:

```java
abstract class RowForm<T> extends BeanValidationForm<T> {
    RowForm(Class<T> type) {
        super(type);
        getContent().setWidthFull();
        getContent().setHeight(null);
    }
}
```

**Proposal.** The default should probably stay (changing it moves every existing
whole-page form), but the embedded case deserves one call with a name:

```java
public BeanValidationForm<T> asSection() {   // undefined height, full width
    getContent().setWidthFull();
    getContent().setHeight(null);
    return this;
}
```

or a constructor flag. The point is that "this form is part of a page" should be a
sentence in the API, not two setter calls whose reason has to be re-explained in a
comment at every site.

## 2. `getFormComponents()` is abstract but irrelevant for custom content

The method exists to feed the default `createContent()` — fields stacked one under
another. Any form that overrides `createContent()` never causes it to be called.
But it is `protected abstract`, so every such form carries:

```java
@Override
protected List<Component> getFormComponents() {
    return List.of();
}
```

Four forms here had that block; one of them also carried a javadoc apologising for
it ("Unused: createContent is overridden…"). That is the tell — an override whose
only content is an apology is an override the API forced.

**Proposal.** Make it non-abstract:

```java
/**
 * The fields for the default layout, in order. Return them and the default
 * createContent() stacks them; override createContent() instead and this is
 * never consulted.
 */
protected List<Component> getFormComponents() {
    return List.of();
}
```

Removing `abstract` is source- and binary-compatible: every existing override keeps
working, and the empty ones can simply be deleted.

## 3. The binding half and the layout half are one class

Both notes above are the same observation from different sides:
`BeanValidationForm` is two things — a binder with save/validation lifecycle, and an
opinionated default layout — and a form with its own layout wants the first without
inheriting the second's assumptions.

**Proposal sketch.** A superclass that owns the binding half and takes content
instead of generating it:

```java
public abstract class BoundForm<T> extends Composite<Div> {
    // FormBinder, setEntity / setEntityWithEnabledSave,
    // setSavedHandler / setEagerSavedHandler, getSaveButton,
    // getClassLevelViolationsDisplay — everything except layout.
    protected abstract Component createContent();
}

public abstract class BeanValidationForm<T> extends BoundForm<T> {
    // adds: getFormComponents() and the default createContent() over it
}
```

With that split, a counter row in this application reads as exactly what it is:

```java
class ExistingCounter extends BoundForm<ChangedCounter> {

    private final TextField comment = new CommentField();
    private final NumberField target = new TargetField();

    @Override
    protected Component createContent() {
        return new FieldRow(comment, target, stopButton);
    }
}
```

No sizing corrections, no empty list, no declined default — the class states its
fields and its face.

## 4. The default save button claims ENTER

`createSaveButton()` returns a `DefaultButton`, which hooks ENTER as its click
shortcut. With one form per view that is a good default. This application has two
bound forms inside one popover — the settings form and the counter starter — and
two `DefaultButton`s would mean one keypress performing two saves.

The workaround overrides the factory to build a button that does not listen:

```java
@Override
protected Button createSaveButton() {
    Button start = new Button(getSaveCaption());
    start.addThemeVariants(ButtonVariant.TERTIARY);
    start.setVisible(false);
    return start;
}
```

— which works, but conflates three decisions (no shortcut, different variant,
initially hidden) into one override, and the shortcut part is invisible in it: you
have to know what `DefaultButton` does to see what plain `Button` here avoids.

**Proposal.** Say it as one thing: `setSaveOnEnter(false)`, or scope the shortcut to
the form's own element (`Shortcuts.addShortcutListener(...).listenOn(this)`) so two
forms on one page each answer for their own fields. The scoped version is the better
default — it makes the collision impossible instead of opt-out.

---

## What this bought in the application

`RowForm` plus the two API notes above removed, per form: two sizing calls with a
comment, a five-line empty override, and in one case an apology javadoc. Across four
forms that was ~40 lines whose only content was "this library default does not apply
here". The forms that remain declare their fields, their content and their handlers
— which is the part that is actually about degree-day counters.

The adjacent finding — rows of fields need to wrap inside a popover, and a
`HorizontalLayout` with the right settings for that is worth naming — is in
`layout-component-proposals.md` §11 with the rest of the overlay story; it is
Vaadin-core territory rather than Viritin's.
