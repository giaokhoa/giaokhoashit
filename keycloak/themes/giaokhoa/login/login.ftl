<#import "template.ftl" as layout>
<#import "passkeys.ftl" as passkeys>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        ${msg("gkLoginTitle")}
    <#elseif section = "form">
        <p class="gk-subtitle">${msg("gkLoginSubtitle")}</p>

        <#-- Lỗi ở cặp trường tài khoản/mật khẩu không đi qua thông báo chung của layout (layout đã tắt
             thông báo khi có lỗi này để tránh trùng), nên trang tự hiển thị lỗi ngay trong thẻ, phía
             trên biểu mẫu. -->
        <#if messagesPerField.existsError('username','password')>
            <div class="gk-alert gk-alert--error" role="alert">
                <span>${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}</span>
            </div>
        </#if>

        <div id="kc-form">
          <div id="kc-form-wrapper">
            <#if realm.password>
                <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                    <#if !usernameHidden??>
                        <#-- Chọn vai trò chỉ đổi nhãn và gợi ý của trường username qua các data-* dưới đây;
                             cả hai vai trò vẫn nộp cùng một trường username của biểu mẫu chuẩn. -->
                        <div class="gk-role-toggle" role="group" aria-label="${msg("gkLoginTitle")}"
                             data-gk-label-teacher="${msg("gkUsernameLabelTeacher")}"
                             data-gk-placeholder-teacher="${msg("gkUsernamePlaceholderTeacher")}"
                             data-gk-label-student="${msg("gkUsernameLabelStudent")}"
                             data-gk-placeholder-student="${msg("gkUsernamePlaceholderStudent")}">
                            <button type="button" class="gk-role-btn is-active" data-gk-role="teacher" aria-pressed="true" tabindex="1">${msg("gkRoleTeacher")}</button>
                            <button type="button" class="gk-role-btn" data-gk-role="student" aria-pressed="false" tabindex="1">${msg("gkRoleStudent")}</button>
                        </div>

                        <div class="gk-field">
                            <label for="username" class="gk-label" id="gk-username-label">${msg("gkUsernameLabelTeacher")}</label>

                            <input tabindex="2" id="username" class="gk-input" name="username" value="${(login.username!'')}" type="text"
                                   placeholder="${msg("gkUsernamePlaceholderTeacher")}"
                                   autofocus autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}"
                                   aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
                                   dir="ltr"
                            />
                        </div>
                    </#if>

                    <div class="gk-field">
                        <label for="password" class="gk-label">${msg("password")}</label>

                        <div class="gk-password-wrap" dir="ltr">
                            <input tabindex="3" id="password" class="gk-input" name="password" type="password" autocomplete="current-password"
                                   aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
                            />
                            <button class="gk-password-toggle" type="button" data-gk-password-toggle aria-label="${msg("showPassword")}"
                                    aria-controls="password" tabindex="4"
                                    data-label-show="${msg("showPassword")}" data-label-hide="${msg("hidePassword")}">
                                <svg data-gk-eye-show viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                                    <circle cx="12" cy="12" r="3"></circle>
                                </svg>
                                <svg data-gk-eye-hide viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                    <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"></path>
                                    <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"></path>
                                    <path d="M14.12 14.12a3 3 0 1 1-4.24-4.24"></path>
                                    <line x1="1" y1="1" x2="23" y2="23"></line>
                                </svg>
                            </button>
                        </div>
                    </div>

                    <div class="gk-options">
                        <div id="kc-form-options">
                            <#if realm.rememberMe && !usernameHidden??>
                                <label class="gk-remember">
                                    <#if login.rememberMe??>
                                        <input tabindex="5" id="rememberMe" name="rememberMe" type="checkbox" checked> ${msg("rememberMe")}
                                    <#else>
                                        <input tabindex="5" id="rememberMe" name="rememberMe" type="checkbox"> ${msg("rememberMe")}
                                    </#if>
                                </label>
                            </#if>
                        </div>
                        <div class="gk-forgot">
                            <#if realm.resetPasswordAllowed>
                                <a tabindex="6" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
                            </#if>
                        </div>
                    </div>

                    <div id="kc-form-buttons">
                        <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                        <input tabindex="7" class="gk-submit" name="login" id="kc-login" type="submit" value="${msg("doLogIn")}"/>
                    </div>
                </form>
            </#if>
            </div>
        </div>
        <@passkeys.conditionalUIData />
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration-container">
                <div id="kc-registration" class="gk-registration">
                    <span>${msg("noAccount")} <a tabindex="8" href="${url.registrationUrl}">${msg("doRegister")}</a></span>
                </div>
            </div>
        </#if>
    <#elseif section = "socialProviders" >
        <#if realm.password && social?? && social.providers?has_content>
            <div id="kc-social-providers" class="gk-social">
                <p class="gk-social-title">${msg("identity-provider-login-label")}</p>

                <ul class="gk-social-list">
                    <#list social.providers as p>
                        <li>
                            <a data-once-link id="social-${p.alias}" class="gk-social-btn" type="button" href="${p.loginUrl}">
                                <#if p.iconClasses?has_content>
                                    <i class="${p.iconClasses!}" aria-hidden="true"></i>
                                    <span class="kc-social-icon-text">${p.displayName!}</span>
                                <#else>
                                    <span>${p.displayName!}</span>
                                </#if>
                            </a>
                        </li>
                    </#list>
                </ul>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
