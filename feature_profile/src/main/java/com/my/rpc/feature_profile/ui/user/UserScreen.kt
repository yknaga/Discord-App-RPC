/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * UserScreen.kt is part of Rpc
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.rpc.feature_profile.ui.user

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.my.rpc.domain.model.user.User
import com.my.rpc.feature_profile.ui.component.Logout
import com.my.rpc.feature_profile.ui.component.ProfileCard
import com.my.rpc.feature_profile.ui.component.ProfileNetworkError
import com.my.rpc.preference.Prefs
import com.my.rpc.resources.R
import com.my.rpc.ui.components.BackButton
import com.my.rpc.ui.components.shimmer.AnimatedShimmer
import com.my.rpc.ui.components.shimmer.ShimmerProfileCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(
    state: UserState,
    onBackPressed: () -> Unit,
    onLogout: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier
            .fillMaxSize(),
        topBar = {
            TopAppBar(title = { },
                navigationIcon = { BackButton { onBackPressed() } })
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                is UserState.Error -> {
                    ProfileNetworkError(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                            .align(Alignment.TopCenter),
                        error = (state.error)
                    )
                    ProfileCard(state.user)
                }

                UserState.Loading -> {
                    AnimatedShimmer {
                        ShimmerProfileCard(brush = it)
                    }
                }

                is UserState.LoadingCompleted -> {
                    ProfileCard(state.user)
                    Logout(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = ctx.getString(R.string.are_you_sure),
                                actionLabel = ctx.getString(R.string.yes),
                                duration = SnackbarDuration.Short,
                                withDismissAction = true
                            ).run {
                                when (this) {
                                    SnackbarResult.ActionPerformed -> {
                                        // `pm clear` cannot work from a normal app:
                                        // it needs the signature/privileged
                                        // CLEAR_APP_USER_DATA permission, so the
                                        // shell command silently failed and the
                                        // stored token was never removed.
                                        // Clear the login state directly instead.
                                        Prefs.clearUserSession()
                                        // The login WebView keeps Discord's own
                                        // session in cookies / local storage.
                                        // Without clearing it, the next login
                                        // would silently reuse the same account.
                                        CookieManager.getInstance().apply {
                                            removeAllCookies(null)
                                            flush()
                                        }
                                        WebStorage.getInstance().deleteAllData()
                                        onLogout()
                                    }

                                    SnackbarResult.Dismissed -> Unit
                                }
                            }

                        }

                    }
                }
            }
        }
    }
}

val fakeUser = User(
    accentColor = null,
    avatar = null,
    avatarDecoration = null,
    badges = null,
    banner = null,
    bannerColor = null,
    discriminator = "3050",
    id = null,
    publicFlags = null,
    username = "yzziK",
    special = null,
    verified = false,
    nitro = true,
    bio = "Hello 👋"
)
@Preview
@Composable
fun UserScreenPreview() {
    UserScreen(
        state = UserState.Loading,
        onBackPressed = {},
    )
}
@Preview
@Composable
fun UserScreenPreview2() {
    UserScreen(
        state = UserState.Error(
            error = "No Internet Connection",
            user = fakeUser
        ),
        onBackPressed = {}
    )
}
@Preview
@Composable
fun UserScreenPreview3() {
    UserScreen(
        state = UserState.LoadingCompleted(
            user = fakeUser
        ),
        onBackPressed = {}
    )
}