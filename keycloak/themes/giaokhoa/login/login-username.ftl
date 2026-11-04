<#import "template.ftl" as layout>
<#import "passkeys.ftl" as passkeys>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        ${msg("gkLoginTitle")}
    <#elseif section = "subtitle">
        <p class="text-muted-foreground text-base">${msg("gkLoginSubtitle")}</p>
    <#elseif section = "form">
        <#-- Trang này là bước đầu của luồng Identity-First: chỉ thu tài khoản, mật khẩu hỏi ở bước sau.
             Lỗi của trường tài khoản hiển thị ngay trong thẻ như ở login.ftl vì layout đã tắt thông báo
             chung khi trường này có lỗi. -->
        <#if messagesPerField.existsError('username')>
            <div class="relative w-full rounded-lg border border-destructive/50 px-4 py-3 text-sm text-destructive" role="alert">
                <span>${kcSanitize(messagesPerField.getFirstError('username'))?no_esc}</span>
            </div>
        </#if>

        <div id="kc-form">
            <div id="kc-form-wrapper">
                <#if realm.password>
                    <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                        <div class="group/field-group @container/field-group flex w-full flex-col data-[slot=checkbox-group]:gap-3 *:data-[slot=field-group]:gap-4 gap-4">
                            <#if !usernameHidden??>
                                <div class="group/field data-[invalid=true]:text-destructive flex w-full flex-col *:w-full [&>.sr-only]:w-auto gap-2"<#if messagesPerField.existsError('username')> data-invalid="true"</#if>>
                                    <#-- Nhãn trường tài khoản theo khuôn theme base: tự đổi theo cấu hình realm,
                                         không chia theo vai trò của người đăng nhập. -->
                                    <label for="username" class="items-center text-sm font-medium select-none group-data-[disabled=true]:pointer-events-none group-data-[disabled=true]:opacity-50 peer-disabled:cursor-not-allowed peer-disabled:opacity-50 group/field-label peer/field-label has-data-checked:border-primary/30 has-data-checked:bg-primary/5 dark:has-data-checked:border-primary/20 dark:has-data-checked:bg-primary/10 flex w-fit gap-2 group-data-[disabled=true]/field:opacity-50 has-[>[data-slot=field]]:rounded-md has-[>[data-slot=field]]:border *:data-[slot=field]:p-3 has-[>[data-slot=field]]:w-full has-[>[data-slot=field]]:flex-col leading-5"><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></label>

                                    <input tabindex="2" id="username" class="border-input file:text-foreground placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:bg-input/30 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 h-9 w-full min-w-0 rounded-md border bg-transparent px-2.5 py-1 text-base shadow-xs transition-[color,box-shadow] outline-none file:inline-flex file:h-7 file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-3 disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:ring-3 md:text-sm" name="username" value="${(login.username!'')}" type="text"
                                           autofocus autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}"
                                           aria-invalid="<#if messagesPerField.existsError('username')>true</#if>"
                                           dir="ltr"
                                    />
                                </div>
                            </#if>

                            <#if realm.rememberMe && !usernameHidden??>
                                <div class="flex items-center justify-between gap-y-2">
                                    <div id="kc-form-options">
                                        <label class="items-center text-sm font-medium select-none group-data-[disabled=true]:pointer-events-none group-data-[disabled=true]:opacity-50 peer-disabled:cursor-not-allowed peer-disabled:opacity-50 group/field-label peer/field-label has-data-checked:border-primary/30 has-data-checked:bg-primary/5 dark:has-data-checked:border-primary/20 dark:has-data-checked:bg-primary/10 flex w-fit gap-2 leading-snug group-data-[disabled=true]/field:opacity-50 has-[>[data-slot=field]]:rounded-md has-[>[data-slot=field]]:border *:data-[slot=field]:p-3 has-[>[data-slot=field]]:w-full has-[>[data-slot=field]]:flex-col text-muted-foreground">
                                            <input tabindex="3" id="rememberMe" class="size-4 shrink-0 accent-primary" name="rememberMe" type="checkbox"<#if login.rememberMe??> checked</#if>> ${msg("rememberMe")}
                                        </label>
                                    </div>
                                </div>
                            </#if>

                            <div id="kc-form-buttons" class="group/field data-[invalid=true]:text-destructive flex w-full gap-3 flex-col *:w-full [&>.sr-only]:w-auto">
                                <input tabindex="4" class="group/button focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 inline-flex shrink-0 items-center justify-center rounded-md border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-all outline-none select-none focus-visible:ring-3 active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-invalid:ring-3 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4 bg-primary text-primary-foreground hover:bg-primary/80 h-9 gap-1.5 px-2.5 in-data-[slot=button-group]:rounded-md has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2 w-full" name="login" id="kc-login" type="submit" value="${msg("doLogIn")}"/>
                            </div>
                        </div>
                    </form>
                </#if>
            </div>
        </div>
        <@passkeys.conditionalUIData />
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration-container">
                <div id="kc-registration">
                    <p class="text-muted-foreground text-center text-base">${msg("noAccount")} <a tabindex="5" class="text-card-foreground hover:underline" href="${url.registrationUrl}">${msg("doRegister")}</a></p>
                </div>
            </div>
        </#if>
    <#elseif section = "socialProviders" >
        <#if realm.password && social?? && social.providers?has_content>
            <div id="kc-social-providers" class="flex flex-col gap-4">
                <div class="flex items-center gap-4">
                    <div class="bg-border data-horizontal:h-px data-horizontal:w-full data-vertical:w-px data-vertical:self-stretch flex-1" data-orientation="horizontal"></div>
                    <p class="text-base">${msg("identity-provider-login-label")}</p>
                    <div class="bg-border data-horizontal:h-px data-horizontal:w-full data-vertical:w-px data-vertical:self-stretch flex-1" data-orientation="horizontal"></div>
                </div>

                <#list social.providers as p>
                    <a data-once-link id="social-${p.alias}" class="group/button focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 inline-flex shrink-0 items-center justify-center rounded-md border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-all outline-none select-none focus-visible:ring-3 active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-invalid:ring-3 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4 hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:hover:bg-muted/50 h-9 gap-1.5 px-2.5 in-data-[slot=button-group]:rounded-md has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2 w-full" href="${p.loginUrl}">
                        <#if p.iconClasses?has_content>
                            <i class="${p.iconClasses!}" aria-hidden="true"></i>
                            <span class="kc-social-icon-text">${p.displayName!}</span>
                        <#else>
                            <span>${p.displayName!}</span>
                        </#if>
                    </a>
                </#list>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
