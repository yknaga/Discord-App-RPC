/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * DiscordLoginWebView.kt is part of Rpc
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.rpc.feature_profile.ui.component

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

const val JS_SNIPPET =
    "javascript:(function()%7Bvar%20i%3Ddocument.createElement('iframe')%3Bdocument.body.appendChild(i)%3Balert(i.contentWindow.localStorage.token.slice(1,-1))%7D)()"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun DiscordLoginWebView(
    onLoginCompleted: (String) -> Unit,
) {
    val url = "https://discord.com/login"
    AndroidView(factory = {
        WebView(it).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            webViewClient = object : WebViewClient() {

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(
                    webView: WebView,
                    url: String,
                ): Boolean {
                    stopLoading()
                    if (url.endsWith("/app")) {
                        loadUrl(JS_SNIPPET)
                        visibility = View.GONE
                    }
                    return false
                }
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            /*
                WebAuthn is disabled in WebView by default, so Discord's
                "Sign in with passkey" (and security keys used as MFA) could
                never work here. Opt in to Web Authentication so passkey
                requests from the login page are bridged to the Android
                Credential Manager.

                The login page (discord.com) is a third-party site that is
                not associated with this app via Digital Asset Links, so
                WEB_AUTHENTICATION_SUPPORT_FOR_APP would not cover it;
                FOR_BROWSER is the mode that allows WebAuthn calls for any
                website. Whether the device's passkey provider ultimately
                honors a request also depends on the provider.
             */
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
                WebSettingsCompat.setWebAuthenticationSupport(
                    settings,
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
                )
            }

            /*
                Motorola users are not able to sign into discord in a WebView:
                This issue is the fault of how Motorola phones (on every model) form the WebKit UA,
                which breaks Discord's UA parsing. This makes the browser unidentifiable.

                see https://github.com/dead8309/Rpc/issues/345#issuecomment-2699729072
             */
            if (android.os.Build.MANUFACTURER.equals(MOTOROLA, ignoreCase = true)) {
                settings.userAgentString = SAMSUNG_USER_AGENT
            }
            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(
                    view: WebView,
                    url: String,
                    message: String,
                    result: JsResult,
                ): Boolean {
                    onLoginCompleted(message)
                    visibility = View.GONE
                    return true
                }
            }
            loadUrl(url)
        }
    })
}
private const val MOTOROLA = "motorola"
private const val SAMSUNG_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.363"