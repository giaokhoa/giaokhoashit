document.addEventListener("DOMContentLoaded", () => {
    initRoleToggle();
    initPasswordToggle();
});

function initRoleToggle() {
    const toggle = document.querySelector("[data-gk-role-toggle]");
    const input = document.getElementById("username");
    const label = document.getElementById("gk-username-label");

    if (!toggle || !input || !label) {
        return;
    }

    toggle.querySelectorAll("[data-gk-role]").forEach((button) => {
        button.addEventListener("click", () => {
            const role = button.dataset.gkRole;

            // Trạng thái chọn nằm ở aria-pressed: vừa là ngữ nghĩa cho trình đọc màn hình, vừa là
            // điều kiện để các lớp aria-pressed: trong CSS đã biên dịch nhuộm nút đang chọn.
            toggle.querySelectorAll("[data-gk-role]").forEach((other) => {
                other.setAttribute("aria-pressed", String(other === button));
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

    button.addEventListener("click", () => {
        const show = input.type === "password";
        input.type = show ? "text" : "password";
        button.querySelector("[data-gk-eye-show]").classList.toggle("hidden", show);
        button.querySelector("[data-gk-eye-hide]").classList.toggle("hidden", !show);
        button.setAttribute("aria-label", show ? button.dataset.labelHide : button.dataset.labelShow);
    });
}
