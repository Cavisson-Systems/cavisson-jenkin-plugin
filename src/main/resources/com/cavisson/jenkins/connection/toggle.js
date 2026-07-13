// True unless the element itself, or an ancestor, is currently display:none - i.e. it's actually
// on screen right now. A trigger nested inside another toggle's group must not force its own
// controlled class(es) visible while that outer group is hidden (otherwise, e.g., the "Diff
// Source" select would keep showing "Commit ID" even while the whole Git Diff group is hidden).
function cavIsOnScreen(el) {
    return el.offsetParent !== null;
}

// Re-dispatches "change" on any toggle trigger (other than self) that is a DOM descendant of one
// of the just-shown elements, so that when an outer toggle reveals a group, an inner toggle
// nested inside that group (e.g. "Diff Source" inside the Git Diff group) re-asserts its own
// narrower visibility instead of being left in whatever stale state it had while hidden. Scoped
// to descendants only (not the whole table) so unrelated triggers elsewhere in the same form
// (e.g. Connection Mode) are never touched - this is what keeps it from ever looping.
function cavRefreshNestedToggles(shownElements, self) {
    shownElements.forEach(function (container) {
        container.querySelectorAll("[data-cav-controls], [data-cav-value-controls]").forEach(function (nested) {
            if (nested !== self) {
                nested.dispatchEvent(new Event("change"));
            }
        });
    });
}

// Cosmetic-only show/hide of the "direct" vs "serviceConnection" connection fields, scoped to
// the nearest enclosing config table so multiple Cavisson steps on the same page don't interfere
// with each other. Never touches Stapler form binding - fields stay submitted/validated exactly
// as before regardless of visibility.
Behaviour.specify(".cav-connection-mode", "cav-connection-mode", 0, function (select) {
    function scope() {
        return select.closest("table") || document;
    }

    function apply() {
        var mode = select.value;
        var shownEls = [];
        scope().querySelectorAll(".cav-conn-direct").forEach(function (el) {
            var show = mode === "direct";
            el.style.display = show ? "" : "none";
            if (show) {
                shownEls.push(el);
            }
        });
        scope().querySelectorAll(".cav-conn-serviceConnection").forEach(function (el) {
            var show = mode === "serviceConnection";
            el.style.display = show ? "" : "none";
            if (show) {
                shownEls.push(el);
            }
        });
        cavRefreshNestedToggles(shownEls, select);
    }

    select.addEventListener("change", apply);
    apply();
});

// Generic "show these rows only while this trigger is active" toggle, driven by a
// data-cav-controls="class1,class2" attribute on the trigger element - a plain (unnamed, never
// submitted) checkbox uses its checked state, any other <input> uses non-empty value. The
// attribute is passed straight through by f:textbox (same as clazz) and is not a databound field
// either way, so this never affects form submission. Used by AnalyseTestFailure (trNumber
// controls the trNumber-only fields).
Behaviour.specify("input[data-cav-controls]", "cav-text-controls-toggle", 0, function (input) {
    function scope() {
        return input.closest("table") || document;
    }

    function apply() {
        var show = cavIsOnScreen(input) && ((input.type === "checkbox") ? input.checked : input.value.trim().length > 0);
        var shownEls = [];
        input.getAttribute("data-cav-controls").split(",").forEach(function (cls) {
            scope().querySelectorAll("." + cls.trim()).forEach(function (el) {
                el.style.display = show ? "" : "none";
                if (show) {
                    shownEls.push(el);
                }
            });
        });
        cavRefreshNestedToggles(shownEls, input);
    }

    input.addEventListener("input", apply);
    input.addEventListener("change", apply);
    apply();
});

// Generic single-select-driven show/hide toggle. The trigger <select> is unnamed (never
// submitted, so this never touches form binding) and carries data-cav-value-controls as a JSON
// map of {optionValue: cssClass | [cssClass, ...]} - the class(es) mapped to the currently
// selected option are shown, every other known class is hidden. A value can map to more than one
// class (e.g. "both" showing both the Tags and Git Diff field groups). Used for "Select
// Testcases By" (Tags / Git Diff / Tags + Git Diff) and "Diff Source" (Commit ID / Merge ID).
Behaviour.specify("select[data-cav-value-controls]", "cav-select-controls-toggle", 0, function (select) {
    function scope() {
        return select.closest("table") || document;
    }

    var map;
    try {
        map = JSON.parse(select.getAttribute("data-cav-value-controls"));
    } catch (e) {
        return;
    }

    function classesFor(value) {
        var classes = map[value];
        return Array.isArray(classes) ? classes : (classes ? [classes] : []);
    }

    var allClasses = Object.keys(map).reduce(function (acc, value) {
        return acc.concat(classesFor(value));
    }, []);

    function apply() {
        var shown = cavIsOnScreen(select) ? classesFor(select.value) : [];
        var shownEls = [];
        allClasses.forEach(function (cls) {
            var show = shown.indexOf(cls) !== -1;
            scope().querySelectorAll("." + cls).forEach(function (el) {
                el.style.display = show ? "" : "none";
                if (show) {
                    shownEls.push(el);
                }
            });
        });
        cavRefreshNestedToggles(shownEls, select);
    }

    select.addEventListener("change", apply);
    apply();
});
