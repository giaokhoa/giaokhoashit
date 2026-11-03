<#import "footer.ftl" as loginFooter>
<#import "theme-resources.ftl" as themeResourceTags>
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${lang}"<#if realm.internationalizationEnabled> dir="${(locale.rtl)?then('rtl','ltr')}"</#if>>

<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />

    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${title!}</title>
    <#if themeResources?? && themeResources.favicons?has_content>
        <@themeResourceTags.renderFavicons themeResources.favicons url.resourcesPath />
    <#else>
        <link rel="icon" href="${url.resourcesPath}/img/favicon.ico" />
    </#if>
    <#if themeResources?? && themeResources.stylesCommon?has_content>
        <@themeResourceTags.renderStyles themeResources.stylesCommon url.resourcesCommonPath />
    <#elseif properties.stylesCommon?has_content>
        <#list properties.stylesCommon?split(' ') as style>
            <link href="${url.resourcesCommonPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if themeResources?? && themeResources.styles?has_content>
        <@themeResourceTags.renderStyles themeResources.styles url.resourcesPath />
    <#elseif properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if themeResources?? && themeResources.scripts?has_content>
        <@themeResourceTags.renderScripts themeResources.scripts url.resourcesPath "text/javascript" />
    <#elseif properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <#-- Các script dưới đây lấy từ theme cha (base): authChecker giữ đồng bộ phiên SSO giữa các tab và
         importmap rfc4648 là điều kiện để khối passkeys trong login.ftl chạy được, nên phải giữ cùng
         khuôn với template của theme cha dù giao diện đã thay toàn bộ. -->
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <script src="${url.resourcesPath}/js/menu-button-links.js" type="module"></script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module">
        <#outputformat "JavaScript">
        import { startSessionPolling } from ${(url.resourcesPath + "/js/authChecker.js")?c};

        startSessionPolling(
            ${url.ssoLoginInOtherTabsUrl?c}
        );
        </#outputformat>
    </script>
    <script type="module">
        document.addEventListener("click", (event) => {
            const link = event.target.closest("a[data-once-link]");

            if (!link) {
                return;
            }

            if (link.getAttribute("aria-disabled") === "true") {
                event.preventDefault();
                return;
            }

            const { disabledClass } = link.dataset;

            if (disabledClass) {
                link.classList.add(...disabledClass.trim().split(/\s+/));
            }

            link.setAttribute("role", "link");
            link.setAttribute("aria-disabled", "true");
        });
    </script>
    <#if authenticationSession??>
        <script type="module">
            <#outputformat "JavaScript">
            import { checkAuthSession } from ${(url.resourcesPath + "/js/authChecker.js")?c};

            checkAuthSession(
                ${authenticationSession.authSessionIdHash?c}
            );
            </#outputformat>
        </script>
    </#if>
</head>

<body class="${properties.kcBodyClass!}" data-page-id="login-${pageId}">
<div class="gk-page">
    <div class="gk-decor" aria-hidden="true">
        <span class="gk-circle gk-circle--left"></span>
        <span class="gk-circle gk-circle--right"></span>
    </div>
    <main class="gk-card">
        <div class="gk-brand">
            <span class="gk-brand-mark" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <polygon points="12 3.5 22 8.5 12 13.5 2 8.5" fill="currentColor" stroke="none"></polygon>
                    <path d="M6.5 10.8v4.2c0 1.4 2.5 2.8 5.5 2.8s5.5-1.4 5.5-2.8v-4.2"></path>
                    <path d="M22 8.5v5"></path>
                </svg>
            </span>
            <span class="gk-brand-name">${msg("gkBrandName")}</span>
        </div>

        <#if auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
            <#nested "show-username">
            <div id="kc-username" class="gk-attempted">
                <span id="kc-attempted-username">${auth.attemptedUsername}</span>
                <a id="reset-login" href="${url.loginRestartFlowUrl}" aria-label="${msg("restartLoginTooltip")}" title="${msg("restartLoginTooltip")}">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <path d="M3 12a9 9 0 1 0 2.9-6.6"></path>
                        <polyline points="3 4 3 9 8 9"></polyline>
                    </svg>
                </a>
            </div>
        <#else>
            <h1 id="kc-page-title" class="gk-title"><#nested "header"></h1>
        </#if>

        <#if displayRequiredFields>
            <p class="gk-required-note"><span class="gk-required">*</span> ${msg("requiredFields")}</p>
        </#if>

        <#-- App-initiated actions should not see warning messages about the need to complete the action during login. -->
        <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
            <div class="gk-alert gk-alert--${message.type}" role="alert">
                <span>${kcSanitize(message.summary)?no_esc}</span>
            </div>
        </#if>

        <#nested "form">

        <#if auth?has_content && auth.showTryAnotherWayLink()>
            <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
                <div class="gk-alt-action">
                    <input type="hidden" name="tryAnotherWay" value="on"/>
                    <a href="#" id="try-another-way"
                       onclick="document.forms['kc-select-try-another-way-form'].requestSubmit();return false;">${msg("doTryAnotherWay")}</a>
                </div>
            </form>
        </#if>

        <#if switchOrganizationEnabled?? && switchOrganizationEnabled>
            <form id="kc-switch-organization-form" action="${url.loginAction}" method="post">
                <div class="gk-alt-action">
                    <input type="hidden" name="switchOrganization" value="true"/>
                    <a href="#" id="switch-organization"
                       onclick="document.forms['kc-switch-organization-form'].requestSubmit();return false;">${msg("doSwitchOrganization")}</a>
                </div>
            </form>
        </#if>

        <#nested "socialProviders">

        <#if displayInfo>
            <div id="kc-info" class="gk-info">
                <#nested "info">
            </div>
        </#if>

        <@loginFooter.content/>
    </main>
</div>
</body>
</html>
</#macro>
