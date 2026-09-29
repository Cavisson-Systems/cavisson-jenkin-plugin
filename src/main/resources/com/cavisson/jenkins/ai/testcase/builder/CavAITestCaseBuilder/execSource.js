// Shows only the field group matching the selected Execution Source
// (exec-source-local / exec-source-git); loaded via <st:adjunct> for CSP compliance.
Behaviour.specify(".cav-ai-exec-source", "cavAiExecSource", 0, function (container) {
    var select = container.querySelector('select[name="_.prdSourceType"]');
    if (!select) {
        return;
    }
    function update() {
        var value = (select.value || "LOCAL").toLowerCase();
        container.querySelectorAll(".exec-source-group").forEach(function (group) {
            group.style.display = group.classList.contains("exec-source-" + value) ? "" : "none";
        });
    }
    select.addEventListener("change", update);
    update();
});
