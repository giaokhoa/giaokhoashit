document.addEventListener("DOMContentLoaded", () => {
    initPasswordToggle();
});

function initPasswordToggle() {
    const button = document.querySelector("[data-gk-password-toggle]");
    const input = document.getElementById("password");

    if (!button || !input) {
        return;
    }

    button.addEventListener("click", () => {
        const show = input.type === "password";
        input.type = show ? "text" : "password";
        button.querySelector("[data-gk-eye-show]").classList.toggle("hidden", show);
        button.querySelector("[data-gk-eye-hide]").classList.toggle("hidden", !show);
        button.setAttribute("aria-label", show ? button.dataset.labelHide : button.dataset.labelShow);
    });
}
