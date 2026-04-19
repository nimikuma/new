(function (document, $) {
    "use strict";

    var SERVLET_PATH = "/bin/oidc/translation/start";
    var DIALOG_ID    = "deepl-translate-dialog";

    // ------------------------------------------------------------------
    // Build a reusable Coral 3 dialog (created once, reused on each click)
    // ------------------------------------------------------------------
    function getOrCreateDialog() {
        var existing = document.getElementById(DIALOG_ID);
        if (existing) { return existing; }

        var dialog = new Coral.Dialog().set({
            id:       DIALOG_ID,
            closable: "on",
            backdrop: "static"
        });
        dialog.header.innerHTML = "Translate with DeepL";

        // Description + path info container
        var desc = document.createElement("p");
        desc.style.marginBottom = "1rem";
        desc.textContent = "Creates a language copy of the selected page(s) and translates all text content using DeepL AI.";
        dialog.content.appendChild(desc);

        // Label
        var label = document.createElement("label");
        label.setAttribute("is", "coral-label");
        label.setAttribute("for", "deepl-lang-select");
        label.textContent = "Target Language";
        dialog.content.appendChild(label);

        // Coral Select — built via Coral API so items are properly upgraded
        var select = new Coral.Select();
        select.id = "deepl-lang-select";
        select.name = "targetLanguage";
        var languages = [
            { value: "EN", label: "English (EN)" },
            { value: "DE", label: "German (DE)" },
            { value: "FR", label: "French (FR)" },
            { value: "ES", label: "Spanish (ES)" },
            { value: "IT", label: "Italian (IT)" },
            { value: "NL", label: "Dutch (NL)" },
            { value: "PL", label: "Polish (PL)" },
            { value: "PT", label: "Portuguese (PT)" }
        ];
        languages.forEach(function (lang) {
            var item = new Coral.Select.Item();
            item.value   = lang.value;
            item.content.textContent = lang.label;
            select.items.add(item);
        });
        dialog.content.appendChild(select);

        // Path info div
        var pathsDiv = document.createElement("div");
        pathsDiv.id = "deepl-selected-paths";
        pathsDiv.style.cssText = "margin-top:0.75rem;font-size:0.875rem;color:#666";
        dialog.content.appendChild(pathsDiv);

        var cancelBtn = new Coral.Button();
        cancelBtn.label.textContent = "Cancel";
        cancelBtn.addEventListener("click", function () { dialog.hide(); });

        var confirmBtn = new Coral.Button();
        confirmBtn.variant = "cta";
        confirmBtn.label.textContent = "Create Copy & Translate";
        confirmBtn.addEventListener("click", function () { startTranslation(dialog); });

        dialog.footer.appendChild(cancelBtn);
        dialog.footer.appendChild(confirmBtn);
        document.body.appendChild(dialog);
        return dialog;
    }

    // ------------------------------------------------------------------
    // Read selected paths from the Sites console collection
    // ------------------------------------------------------------------
    function getSelectedPaths() {
        var paths = [];
        $(".foundation-selections-item").each(function () {
            var path = $(this).data("foundationCollectionItemId")
                || $(this).attr("data-foundation-collection-item-id");
            if (path && paths.indexOf(path) === -1) {
                paths.push(path);
            }
        });
        return paths;
    }

    // ------------------------------------------------------------------
    // POST to servlet
    // ------------------------------------------------------------------
    function startTranslation(dialog) {
        var lang  = dialog.querySelector("#deepl-lang-select").value;
        var paths = dialog._selectedPaths || [];

        if (!paths.length) {
            showToast("error", "No pages selected.");
            return;
        }

        dialog.hide();

        var formData = new FormData();
        formData.append("targetLanguage", lang);
        paths.forEach(function (p) { formData.append("path", p); });

        var token = "";
        try { token = Granite.csrf.getToken(); } catch (e) {}
        if (!token) {
            var tokenMeta = document.querySelector('meta[name="_csrf_token"]');
            token = tokenMeta ? tokenMeta.getAttribute("content") : "";
        }
        if (token) { formData.append(":cq_csrf_token", token); }

        $.ajax({
            url:         SERVLET_PATH,
            type:        "POST",
            data:        formData,
            processData: false,
            contentType: false,
            success: function (res) {
                var allOk = res && res.results
                    && res.results.every(function (r) { return r.status === "completed"; });
                showToast(
                    allOk ? "success" : "error",
                    allOk
                        ? "Translation completed for " + paths.length + " page(s)."
                        : "Translation failed for some pages. Check error logs."
                );
            },
            error: function (xhr) {
                showToast("error", "Request failed (" + xhr.status + "): " + xhr.responseText);
            }
        });
    }

    function showToast(variant, message) {
        var ui = $(window).adaptTo("foundation-ui");
        if (ui) {
            ui.notify(null, message, variant === "success" ? "success" : "error");
        } else {
            alert(message);
        }
    }

    // ------------------------------------------------------------------
    // Button click — AEM fires a click on the element carrying granite:rel class
    // ------------------------------------------------------------------
    $(document).on("click", ".cq-siteadmin-admin-actions-deepl-translate-activator", function (e) {
        e.preventDefault();

        var paths = getSelectedPaths();
        if (!paths.length) {
            showToast("error", "Select at least one page first.");
            return;
        }

        var dialog = getOrCreateDialog();
        dialog._selectedPaths = paths;

        var infoEl = dialog.querySelector("#deepl-selected-paths");
        if (infoEl) {
            infoEl.innerHTML = "<b>Selected page(s):</b><br>"
                + paths.map(function (p) { return "&nbsp;&bull; " + p; }).join("<br>");
        }

        dialog.show();
    });

}(document, jQuery));
