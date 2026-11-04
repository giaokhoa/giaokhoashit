<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true displayMessage=!messagesPerField.existsError('username'); section>
    <#if section = "header">
        ${msg("emailForgotTitle")}
    <#elseif section = "form">
        <#if messagesPerField.existsError('username')>
            <div class="relative w-full rounded-lg border border-destructive/50 px-4 py-3 text-sm text-destructive" role="alert">
                <span>${kcSanitize(messagesPerField.getFirstError('username'))?no_esc}</span>
            </div>
        </#if>

        <form id="kc-reset-password-form" action="${url.loginAction}" method="post">
            <div class="group/field-group @container/field-group flex w-full flex-col data-[slot=checkbox-group]:gap-3 *:data-[slot=field-group]:gap-4 gap-4">
                <div class="group/field data-[invalid=true]:text-destructive flex w-full flex-col *:w-full [&>.sr-only]:w-auto gap-2"<#if messagesPerField.existsError('username')> data-invalid="true"</#if>>
                    <label for="username" class="items-center text-sm font-medium select-none group-data-[disabled=true]:pointer-events-none group-data-[disabled=true]:opacity-50 peer-disabled:cursor-not-allowed peer-disabled:opacity-50 group/field-label peer/field-label has-data-checked:border-primary/30 has-data-checked:bg-primary/5 dark:has-data-checked:border-primary/20 dark:has-data-checked:bg-primary/10 flex w-fit gap-2 group-data-[disabled=true]/field:opacity-50 has-[>[data-slot=field]]:rounded-md has-[>[data-slot=field]]:border *:data-[slot=field]:p-3 has-[>[data-slot=field]]:w-full has-[>[data-slot=field]]:flex-col leading-5"><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></label>

                    <input type="text" id="username" name="username" class="border-input file:text-foreground placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:bg-input/30 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 h-9 w-full min-w-0 rounded-md border bg-transparent px-2.5 py-1 text-base shadow-xs transition-[color,box-shadow] outline-none file:inline-flex file:h-7 file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-3 disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:ring-3 md:text-sm" autofocus value="${(auth.attemptedUsername!'')}"
                           aria-invalid="<#if messagesPerField.existsError('username')>true</#if>" dir="ltr"/>
                </div>

                <div class="flex items-center justify-between gap-y-2">
                    <div></div>
                    <div>
                        <a class="text-base text-nowrap hover:underline" href="${url.loginUrl}">${msg("backToLogin")}</a>
                    </div>
                </div>

                <div id="kc-form-buttons" class="group/field data-[invalid=true]:text-destructive flex w-full gap-3 flex-col *:w-full [&>.sr-only]:w-auto">
                    <input class="group/button focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 inline-flex shrink-0 items-center justify-center rounded-md border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-all outline-none select-none focus-visible:ring-3 active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-invalid:ring-3 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4 bg-primary text-primary-foreground hover:bg-primary/80 h-9 gap-1.5 px-2.5 in-data-[slot=button-group]:rounded-md has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2 w-full" type="submit" value="${msg("doSubmit")}"/>
                </div>
            </div>
        </form>
    <#elseif section = "info" >
        <#if realm.duplicateEmailsAllowed>
            ${msg("emailInstructionUsername")}
        <#else>
            ${msg("emailInstruction")}
        </#if>
    </#if>
</@layout.registrationLayout>
