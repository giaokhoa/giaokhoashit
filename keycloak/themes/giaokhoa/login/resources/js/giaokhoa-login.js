document.addEventListener("DOMContentLoaded", () => {
    initRoleToggle();
    initPasswordToggle();
});

function initRoleToggle() {
    const toggle = document.querySelector(".gk-role-toggle");
    const input = document.getElementById("username");
    const label = document.getElementById("gk-username-label");

    if (!toggle || !input || !label) {
        return;
    }

    toggle.querySelectorAll("[data-gk-role]").forEach((button) => {
        button.addEventListener("click", () => {
            const role = button.dataset.gkRole;

            toggle.querySelectorAll("[data-gk-role]").forEach((other) => {
                const active = other === button;
                other.classList.toggle("is-active", active);
                other.setAttribute("aria-pressed", String(active));
            });

            // Chỉ thay nhãn và gợi ý; giá trị người dùng đã nhập trong trường username giữ nguyên
            // và biểu mẫu vẫn nộp về cùng một endpoint đăng nhập của Keycloak.
            label.textContent = role === "teacher" ? toggle.dataset.gkLabelTeacher : toggle.dataset.gkLabelStudent;
            input.placeholder = role === "teacher" ? toggle.dataset.gkPlaceholderTeacher : toggle.dataset.gkPlaceholderStudent;
        });
    });
}

function initPasswordToggle() {
    const button = document.querySelector("[data-gk-password-toggle]");
    const input = document.getElementById("password");

    if (!button || !input) {
        return;
    }

    const eyeShow = button.querySelector("[data-gk-eye-show]");
    const eyeHide = button.querySelector("[data-gk-eye-hide]");

    button.addEventListener("click", () => {
        const show = input.type === "password";
        input.type = show ? "text" : "password";
        button.setAttribute("aria-label", show ? button.dataset.labelHide : button.dataset.labelShow);
        if (eyeShow) {
            eyeShow.hidden = show;
        }
        if (eyeHide) {
            eyeHide.hidden = !show;
        }
    });
}
