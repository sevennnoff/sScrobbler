package com.sscrobbler.app.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sscrobbler.app.ui.theme.CardShape
import com.sscrobbler.app.ui.theme.PillShape
import com.sscrobbler.app.ui.viewmodel.LastFmAuthState
import com.sscrobbler.app.ui.viewmodel.OnboardingUiState
import com.sscrobbler.app.ui.viewmodel.OnboardingViewModel

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkNotificationAccess(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Step progress dots
        StepIndicators(
            currentStep = state.currentStep,
            totalSteps = 3
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Expressive Animated Page Transition
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut()
                        )
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut()
                        )
                    }
                },
                label = "onboardingPager"
            ) { step ->
                when (step) {
                    0 -> OnboardingStep1Value(
                        onNext = { viewModel.nextStep() }
                    )
                    1 -> OnboardingStep2Permissions(
                        isNotificationGranted = state.isNotificationAccessGranted,
                        isBatteryIgnored = state.isBatteryOptimizationIgnored,
                        onGrantNotification = {
                            viewModel.openNotificationSettings(context)
                        },
                        onGrantBattery = {
                            viewModel.requestIgnoreBatteryOptimization(context)
                        },
                        onCheck = {
                            viewModel.checkAllPermissions(context)
                        },
                        onNext = { viewModel.nextStep() },
                        onBack = { viewModel.prevStep() }
                    )
                    2 -> OnboardingStep3Auth(
                        authState = state.authState,
                        onStartAuth = { viewModel.startBrowserAuth(context) },
                        onConfirmAuth = { viewModel.confirmBrowserAuth() },
                        onCancelAuth = { viewModel.cancelAuth() },
                        onDisconnect = { viewModel.disconnect() },
                        onComplete = {
                            viewModel.completeOnboarding()
                            onFinished()
                        },
                        onBack = { viewModel.prevStep() }
                    )
                }
            }
        }
    }
}

@Composable
fun StepIndicators(currentStep: Int, totalSteps: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until totalSteps) {
            val isActive = i == currentStep
            Box(
                modifier = Modifier
                    .size(
                        width = if (isActive) 28.dp else 10.dp,
                        height = 10.dp
                    )
                    .background(
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Step 1: Presentation of Value
// ─────────────────────────────────────────────────────────────
@Composable
fun OnboardingStep1Value(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(top = 20.dp)
        ) {
            // Hero Icon Container with Gradient
            Surface(
                modifier = Modifier.size(130.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(68.dp)
                    )
                }
            }

            Text(
                text = "sScrobbler",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "\"Scrobble when you're actually done listening. No premature 50% scrobbles.\"",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ValuePropRow(
                        title = "Real Listening Verification",
                        desc = "Only scrobbles when a track completes naturally or switches — never on pause-and-abandon."
                    )
                    ValuePropRow(
                        title = "Universal Media Player Support",
                        desc = "Works seamlessly with Spotify, Qobuz, Apple Music, YouTube Music, Symfonium, Poweramp and more."
                    )
                    ValuePropRow(
                        title = "Offline Resilient & Deduplicated",
                        desc = "Queues tracks safely in Room DB with SHA-256 deduplication and batches up to 50 tracks on reconnect."
                    )
                }
            }
        }

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            shape = PillShape
        ) {
            Text(
                text = "Get Started",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
fun ValuePropRow(title: String, desc: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Step 2: Permissions & Background Activity
// ─────────────────────────────────────────────────────────────
@Composable
fun OnboardingStep2Permissions(
    isNotificationGranted: Boolean,
    isBatteryIgnored: Boolean,
    onGrantNotification: () -> Unit,
    onGrantBattery: () -> Unit,
    onCheck: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        onCheck()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 16.dp)
        ) {
            val allGood = isNotificationGranted && isBatteryIgnored
            Surface(
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
                color = if (allGood) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (allGood) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = if (allGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Text(
                text = "Permissions & Background",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "sScrobbler needs notification access to capture playing music, and unrestricted battery access so Android doesn't kill the scrobbler service in the background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Card 1: Notification Access (Required)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isNotificationGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isNotificationGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Column {
                                Text(
                                    text = "Notification Access",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isNotificationGranted) "Active • Captures music sessions" else "Required • Missing permission",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isNotificationGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (!isNotificationGranted) {
                            Button(
                                onClick = onGrantNotification,
                                shape = PillShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Enable")
                            }
                        }
                    }
                }
            }

            // Card 2: Unrestricted Battery (Recommended)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isBatteryIgnored) Icons.Default.BatteryChargingFull else Icons.Default.BatteryAlert,
                                contentDescription = null,
                                tint = if (isBatteryIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                            Column {
                                Text(
                                    text = "Unrestricted Battery",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isBatteryIgnored) "Unrestricted • Will not be killed" else "Recommended • Avoid background sleep",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isBatteryIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!isBatteryIgnored) {
                            OutlinedButton(
                                onClick = onGrantBattery,
                                shape = PillShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Allow")
                            }
                        }
                    }
                }
            }

            TextButton(
                onClick = onCheck,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Refresh status")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = PillShape
            ) {
                Text("Back")
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f),
                shape = PillShape
            ) {
                Text(if (isNotificationGranted) "Continue" else "Skip for now")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Step 3: Last.fm Authorization
// ─────────────────────────────────────────────────────────────
@Composable
fun OnboardingStep3Auth(
    authState: LastFmAuthState,
    onStartAuth: () -> Unit,
    onConfirmAuth: () -> Unit,
    onCancelAuth: () -> Unit,
    onDisconnect: () -> Unit,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(top = 20.dp)
        ) {
            Surface(
                modifier = Modifier.size(110.dp),
                shape = CircleShape,
                color = if (authState is LastFmAuthState.Connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (authState is LastFmAuthState.Connected) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = if (authState is LastFmAuthState.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            Text(
                text = "Connect Last.fm",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            when (authState) {
                is LastFmAuthState.Connected -> {
                    Text(
                        text = "Connected as @${authState.username}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Your scrobbles and Now Playing status are ready to be published to your Last.fm account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        shape = PillShape
                    ) {
                        Text("Switch / Change Account")
                    }
                }
                is LastFmAuthState.WaitingForBrowser -> {
                    Text(
                        text = "Please approve authorization in your browser, then tap 'Confirm Login' below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onConfirmAuth,
                        modifier = Modifier.fillMaxWidth(),
                        shape = PillShape
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Confirm Login")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onStartAuth,
                            modifier = Modifier.weight(1f),
                            shape = PillShape
                        ) {
                            Text("Reopen Browser", style = MaterialTheme.typography.labelSmall)
                        }
                        androidx.compose.material3.TextButton(
                            onClick = onCancelAuth,
                            modifier = Modifier.weight(1f),
                            shape = PillShape
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                is LastFmAuthState.LoadingToken, is LastFmAuthState.FetchingSession -> {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Text(
                        text = "Connecting with Last.fm...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is LastFmAuthState.Error -> {
                    Text(
                        text = authState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onStartAuth,
                            modifier = Modifier.weight(1f),
                            shape = PillShape
                        ) {
                            Text("Try Again")
                        }
                        OutlinedButton(
                            onClick = onCancelAuth,
                            modifier = Modifier.weight(1f),
                            shape = PillShape
                        ) {
                            Text("Cancel")
                        }
                    }
                }
                LastFmAuthState.Idle -> {
                    Text(
                        text = "Authorize sScrobbler to submit scrobbles to your Last.fm profile via web authentication.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onStartAuth,
                        modifier = Modifier.fillMaxWidth(),
                        shape = PillShape
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Last.fm Login")
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = PillShape
            ) {
                Text("Back")
            }
            Button(
                onClick = onComplete,
                modifier = Modifier.weight(1f),
                shape = PillShape
            ) {
                Text(if (authState is LastFmAuthState.Connected) "Finish Setup" else "Finish & Later")
            }
        }
    }
}
